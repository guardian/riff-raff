package deployment.preview

import java.util.UUID

import akka.actor.ActorSystem
import akka.agent.Agent
import com.gu.management.Loggable
import conf.Configuration
import magenta.artifact.S3YamlArtifact
import magenta.deployment_type.DeploymentType
import magenta.{DeployParameters, DeployReporter, DeploymentResources}
import resources.PrismLookup

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class PreviewCoordinator(prismLookup: PrismLookup, deploymentTypes: Seq[DeploymentType]) extends Loggable {
  implicit lazy val system = ActorSystem("preview")
  val agent = Agent[Map[UUID, PreviewResult]](Map.empty)

  def cleanupPreviews() {
    agent.send { resultMap =>
      resultMap.filter { case (uuid, result) =>
        !result.future.isCompleted || result.duration.toStandardMinutes.getMinutes < 60
      }
    }
  }

  def startPreview(parameters: DeployParameters): Either[String, UUID] = {
    cleanupPreviews()

    val previewId = UUID.randomUUID()
    logger.info(s"Starting preview for $previewId")
    val muteLogger = DeployReporter.rootReporterFor(previewId, parameters, publishMessages = false)
    val resources = DeploymentResources(muteLogger, prismLookup, Configuration.artifact.aws.client)
    val artifact = S3YamlArtifact.apply(parameters.build, conf.Configuration.artifact.aws.bucketName)
    val maybeConfig = artifact.deployObject.fetchContentAsString()(resources.artifactClient)

    maybeConfig match {
      case Some(config) =>
        logger.info(s"Got configuration for $previewId - resolving")
        val previewFuture = Future(Preview(artifact, config, parameters, resources, deploymentTypes))
        Await.ready(agent.alter{ _ + (previewId -> PreviewResult(previewFuture)) }, 30.second)
        Right(previewId)
      case None =>
        Left(s"YAML configuration file for ${parameters.build.projectName} ${parameters.build.id} not found")
    }

  }

  def getPreviewResult(id: UUID): Option[PreviewResult] = agent().get(id)
}