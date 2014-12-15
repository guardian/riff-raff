package deployment

import _root_.resources.LookupSelector
import magenta._
import persistence.Persistence
import java.io.File
import io.Source
import magenta.json.JsonReader
import controllers.routes
import magenta.DeployParameters
import magenta.Project
import magenta.Build
import tasks.{ Task => MagentaTask }
import magenta.teamcity.Artifact
import java.util.UUID
import akka.agent.Agent
import org.joda.time.DateTime
import conf.Configuration
import scala.concurrent._
import akka.actor.ActorSystem
import ExecutionContext.Implicits.global
import duration._

case class PreviewResult(future: Future[Preview], startTime: DateTime = new DateTime()) {
  def completed = future.isCompleted
  def duration = new org.joda.time.Duration(startTime, new DateTime())
}

object PreviewController {
  implicit lazy val system = ActorSystem("preview")
  val agent = Agent[Map[UUID, PreviewResult]](Map.empty)

  def cleanupPreviews() {
    agent.send { resultMap =>
      resultMap.filter { case (uuid, result) =>
        !result.completed || result.duration.toStandardMinutes.getMinutes < 60
      }
    }
  }

  def startPreview(parameters: DeployParameters): UUID = {
    cleanupPreviews()
    val previewId = UUID.randomUUID()
    val previewFuture = future { Preview(parameters) }
    Await.ready(agent.alter{ _ + (previewId -> PreviewResult(previewFuture)) }, 1.second)
    previewId
  }

  def getPreview(id: UUID, parameters: DeployParameters): Option[PreviewResult] = agent().get(id)
}

object Preview {
  def getJsonFromStore(build: Build): Option[String] = Persistence.store.getDeployJson(build)
  def getJsonFromArtifact(build: Build): String = Artifact.withDownload(Configuration.teamcity.serverURL, build) { artifactDir =>
    Source.fromFile(new File(artifactDir, "deploy.json")).getLines().mkString
  }
  def parseJson(json:String) = JsonReader.parse(json, new File(System.getProperty("java.io.tmpdir")))

  /**
   * Get the project for the build for preview purposes.  The project returned will not have a valid artifactDir so
   * cannot be used for an actual deploy.
   * This is cached in the database so future previews are faster.
   */
  def getProject(build: Build): Project = {
    val json = getJsonFromStore(build).getOrElse {
      val json = getJsonFromArtifact(build)
      Persistence.store.writeDeployJson(build, json)
      json
    }
    parseJson(json)
  }

  /**
   * Get the preview, extracting the artifact if necessary - this may take a long time to run
   */
  def apply(parameters: DeployParameters): Preview = {
    val project = Preview.getProject(parameters.build)
    Preview(project, parameters)
  }
}

case class Preview(project: Project, parameters: DeployParameters) {
  lazy val lookup = LookupSelector()
  lazy val stacks = Resolver.resolveStacks(project, parameters) collect {
    case NamedStack(s) => s
  }
  lazy val recipeNames = recipeTasks.map(_.recipe.name).distinct
  lazy val allRecipes = project.recipes.values.map(_.name).toList.sorted
  lazy val dependantRecipes = recipeNames.filterNot(_ == recipe)
  def isDependantRecipe(r: String) = r != recipe && recipeNames.contains(r)
  def dependsOn(r: String) = project.recipes(r).dependsOn

  lazy val recipeTasks = Resolver.resolveDetail(project, lookup, parameters)
  lazy val tasks = recipeTasks.flatMap(_.tasks)

  def taskHosts(taskList:List[MagentaTask]) = taskList.flatMap(_.taskHost).filter(lookup.hosts.all.contains).distinct

  lazy val hosts = taskHosts(tasks)
  lazy val allHosts = {
    val allTasks = Resolver.resolve(project, lookup, parameters.copy(recipe = RecipeName(recipe), hostList=Nil)).distinct
    taskHosts(allTasks)
  }
  lazy val allPossibleHosts = {
    val allTasks = allRecipes.flatMap(recipe => Resolver.resolve(project, lookup, parameters.copy(recipe = RecipeName(recipe), hostList=Nil))).distinct
    taskHosts(allTasks)
  }

  val projectName = parameters.build.projectName
  val build = parameters.build.id
  val stage = parameters.stage.name
  val recipe = parameters.recipe.name
  val hostList = parameters.hostList.mkString(",")

  def withRecipe(newRecipe: String) = routes.DeployController.preview(projectName, build, stage, newRecipe, "", "")
}