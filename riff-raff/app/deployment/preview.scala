package deployment

import java.util.UUID

import akka.actor.ActorSystem
import akka.agent.Agent
import conf.Configuration
import controllers.routes
import magenta.artifact.{S3JsonArtifact, S3YamlArtifact}
import magenta.graph.DeploymentGraph
import magenta.json.JsonReader
import magenta.tasks.{Task => MagentaTask}
import magenta.{Build, DeployParameters, Project, _}
import org.joda.time.DateTime
import resources.PrismLookup

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
      resultMap.filter {
        case (uuid, result) =>
          !result.completed || result.duration.toStandardMinutes.getMinutes < 60
      }
    }
  }

  def startPreview(parameters: DeployParameters, prismLookup: PrismLookup): UUID = {
    cleanupPreviews()
    val previewId = UUID.randomUUID()
    val muteLogger = DeployReporter.rootReporterFor(previewId, parameters, publishMessages = false)
    val resources = DeploymentResources(muteLogger, prismLookup, Configuration.artifact.aws.client)
    val previewFuture = Future { Preview(parameters, resources) }
    Await.ready(agent.alter { _ + (previewId -> PreviewResult(previewFuture)) }, 30.second)
    previewId
  }

  def getPreview(id: UUID, parameters: DeployParameters): Option[PreviewResult] = agent().get(id)
}

object Preview {
  import Configuration._

  /**
    * Get the project for the build for preview purposes.
    */
  def getProject(build: Build, resources: DeploymentResources): Project = {
    val yamlArtifact = S3YamlArtifact(build, artifact.aws.bucketName)
    if (yamlArtifact.deployObject.fetchContentAsString()(resources.artifactClient).isDefined)
      throw new IllegalArgumentException("Preview does not currently support riff-raff.yaml configuration files")
    val s3Artifact = S3JsonArtifact(build, artifact.aws.bucketName)
    val json = S3JsonArtifact.withZipFallback(s3Artifact) { artifact =>
      Try(artifact.deployObject.fetchContentAsString()(resources.artifactClient).get)
    }(resources.artifactClient, resources.reporter)
    JsonReader.parse(json, s3Artifact)
  }

  /**
    * Get the preview, extracting the artifact if necessary - this may take a long time to run
    */
  def apply(parameters: DeployParameters, resources: DeploymentResources): Preview = {
    val project = Preview.getProject(parameters.build, resources)
    Preview(project, parameters, resources, Region(target.aws.deployJsonRegionName))
  }
}

case class Preview(project: Project, parameters: DeployParameters, resources: DeploymentResources, region: Region) {
  lazy val stacks = Resolver.resolveStacks(project, parameters, resources.reporter) collect {
    case NamedStack(s) => s
  }
  lazy val recipeNames = recipeTasks.map(_.recipe.name).distinct
  lazy val allRecipes = project.recipes.values.map(_.name).toList.sorted
  lazy val dependantRecipes = recipeNames.filterNot(_ == recipe)
  def isDependantRecipe(r: String) = r != recipe && recipeNames.contains(r)
  def dependsOn(r: String) = project.recipes(r).dependsOn

  lazy val recipeTasks = Resolver.resolveDetail(project, parameters, resources, region)
  lazy val tasks = recipeTasks.flatMap(_.tasks)

  def taskHosts(taskList: List[MagentaTask]) =
    taskList.flatMap(_.taskHost).filter(resources.lookup.hosts.all.contains).distinct

  lazy val hosts = taskHosts(tasks)
  lazy val allHosts = {
    val tasks =
      Resolver.resolve(project, parameters.copy(recipe = RecipeName(recipe), hostList = Nil), resources, region)
    val allTasks = DeploymentGraph.toTaskList(tasks)
    taskHosts(allTasks)
  }
  lazy val allPossibleHosts = {
    val allTasks = allRecipes.flatMap { recipe =>
      val graph =
        Resolver.resolve(project, parameters.copy(recipe = RecipeName(recipe), hostList = Nil), resources, region)
      DeploymentGraph.toTaskList(graph)
    }.distinct
    taskHosts(allTasks)
  }

  val projectName = parameters.build.projectName
  val build = parameters.build.id
  val stage = parameters.stage.name
  val recipe = parameters.recipe.name
  val hostList = parameters.hostList.mkString(",")

  def withRecipe(newRecipe: String) = routes.DeployController.preview(projectName, build, stage, newRecipe, "", "")
}
