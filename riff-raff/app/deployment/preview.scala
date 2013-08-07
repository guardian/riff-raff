package deployment

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
import akka.actor.{Actor, Props, ActorSystem}
import conf.Configuration

trait PreviewResult {
  def completed: Boolean
}
case class PreviewReady(preview: Preview) extends PreviewResult { val completed = true }
case class PreviewFailed(exception: Exception) extends PreviewResult { val completed = true }
case class PreviewInProgress(startTime: DateTime = new DateTime()) extends PreviewResult { val completed = false }

object PreviewController {
  implicit lazy val system = ActorSystem("preview")
  val agent = Agent[Map[UUID, PreviewResult]](Map.empty)
  lazy val actor = system.actorOf(Props[PreviewActor])

  def startPreview(parameters: DeployParameters): UUID = {
    val previewId = UUID.randomUUID()
    val previewResult = PreviewInProgress(new DateTime())
    agent.send{ _ + (previewId -> previewResult) }
    actor ! (previewId, parameters)
    previewId
  }

  def getPreview(id: UUID, parameters: DeployParameters): PreviewResult = {
    val result = agent()(id)
    if (result.completed) agent.send( _ - id )
    result
  }
}

class PreviewActor extends Actor {
  def receive = {
    case (id:UUID, parameters:DeployParameters) =>
      try {
        val result = Preview(parameters)
        PreviewController.agent.send( _ + (id -> PreviewReady(result)))
      } catch {
        case e:Exception =>
          PreviewController.agent.send( _ + (id -> PreviewFailed(e)))
      }
  }
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
  lazy val deployInfo = DeployInfoManager.deployInfo
  lazy val recipeNames = recipeTasks.map(_.recipe.name)
  lazy val allRecipes = project.recipes.values.map(_.name).toList.sorted
  lazy val dependantRecipes = recipeNames.filterNot(_ == recipe)
  def isDependantRecipe(r: String) = r != recipe && recipeNames.contains(r)
  def dependsOn(r: String) = project.recipes(r).dependsOn

  lazy val recipeTasks = Resolver.resolveDetail(project, deployInfo, parameters)
  lazy val tasks = recipeTasks.flatMap(_.tasks)

  def taskHosts(taskList:List[MagentaTask]) = taskList.flatMap(_.taskHost).filter(deployInfo.hosts.contains).distinct

  lazy val hosts = taskHosts(tasks)
  lazy val allHosts = {
    val allTasks = Resolver.resolve(project, deployInfo, parameters.copy(recipe = RecipeName(recipe), hostList=Nil)).distinct
    taskHosts(allTasks)
  }
  lazy val allPossibleHosts = {
    val allTasks = allRecipes.flatMap(recipe => Resolver.resolve(project, deployInfo, parameters.copy(recipe = RecipeName(recipe), hostList=Nil))).distinct
    taskHosts(allTasks)
  }

  val projectName = parameters.build.projectName
  val build = parameters.build.id
  val stage = parameters.stage.name
  val recipe = parameters.recipe.name
  val hostList = parameters.hostList.mkString(",")

  def withRecipe(newRecipe: String) = routes.Deployment.preview(projectName, build, stage, newRecipe, "")
}