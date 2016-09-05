package deployment

import java.util.UUID

import resources.LookupSelector
import akka.actor.ActorSystem
import akka.agent.Agent
import com.amazonaws.services.s3.AmazonS3
import conf.Configuration
import controllers.routes
import magenta.artifact.S3Artifact
import magenta.json.JsonReader
import magenta.tasks.{Task => MagentaTask}
import magenta.{Build, DeployParameters, Project, _}
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Try

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
    val muteLogger = DeployReporter.rootReporterFor(previewId, parameters, publishMessages = false)
    val previewFuture = Future { Preview(parameters, muteLogger) }
    Await.ready(agent.alter{ _ + (previewId -> PreviewResult(previewFuture)) }, 30.second)
    previewId
  }

  def getPreview(id: UUID, parameters: DeployParameters): Option[PreviewResult] = agent().get(id)
}

object Preview {
  import Configuration.artifact.aws._

  /**
   * Get the project for the build for preview purposes.
   */
  def getProject(build: Build, reporter: DeployReporter): Project = {
    val s3Artifact = S3Artifact(build, bucketName)
    val json = S3Artifact.withZipFallback(s3Artifact){ artifact =>
      Try(artifact.deployObject.fetchContentAsString()(client).get)
    }(client, reporter)
    JsonReader.parse(json, s3Artifact)
  }

  /**
   * Get the preview, extracting the artifact if necessary - this may take a long time to run
   */
  def apply(parameters: DeployParameters, reporter: DeployReporter): Preview = {
    val project = Preview.getProject(parameters.build, reporter)
    Preview(project, parameters, reporter, client)
  }
}

case class Preview(project: Project, parameters: DeployParameters, reporter: DeployReporter, artifactClient: AmazonS3) {
  lazy val lookup = LookupSelector()
  lazy val stacks = Resolver.resolveStacks(project, parameters) collect {
    case NamedStack(s) => s
  }
  lazy val recipeNames = recipeTasks.map(_.recipe.name).distinct
  lazy val allRecipes = project.recipes.values.map(_.name).toList.sorted
  lazy val dependantRecipes = recipeNames.filterNot(_ == recipe)
  def isDependantRecipe(r: String) = r != recipe && recipeNames.contains(r)
  def dependsOn(r: String) = project.recipes(r).dependsOn

  lazy val recipeTasks = Resolver.resolveDetail(project, lookup, parameters, reporter, artifactClient)
  lazy val tasks = recipeTasks.flatMap(_.tasks)

  def taskHosts(taskList:List[MagentaTask]) = taskList.flatMap(_.taskHost).filter(lookup.hosts.all.contains).distinct

  lazy val hosts = taskHosts(tasks)
  lazy val allHosts = {
    val tasks = Resolver.resolve(project, lookup, parameters.copy(recipe = RecipeName(recipe), hostList=Nil), reporter, artifactClient)
    val allTasks = tasks.toTaskList
    taskHosts(allTasks)
  }
  lazy val allPossibleHosts = {
    val allTasks = allRecipes.flatMap(recipe =>
      Resolver.resolve(project, lookup, parameters.copy(recipe = RecipeName(recipe), hostList=Nil), reporter, artifactClient).toTaskList
    ).distinct
    taskHosts(allTasks)
  }

  val projectName = parameters.build.projectName
  val build = parameters.build.id
  val stage = parameters.stage.name
  val recipe = parameters.recipe.name
  val hostList = parameters.hostList.mkString(",")

  def withRecipe(newRecipe: String) = routes.DeployController.preview(projectName, build, stage, newRecipe, "", "")
}