package deployment.preview

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import cats.syntax.either._
import com.gu.management.Loggable
import conf.Configuration
import magenta.artifact.S3YamlArtifact
import magenta.deployment_type.DeploymentType
import magenta.{DeployParameters, DeployReporter, DeploymentResources}
import resources.PrismLookup

import scala.collection.JavaConverters._
import scala.collection.concurrent.{Map => ConcurrentMap}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PreviewCoordinator(prismLookup: PrismLookup, deploymentTypes: Seq[DeploymentType]) extends Loggable {
  private val previews: ConcurrentMap[UUID, PreviewResult] = new ConcurrentHashMap[UUID, PreviewResult]().asScala

  def cleanupPreviews() {
    previews.retain{(uuid, result) =>
      !result.future.isCompleted || result.duration.toStandardMinutes.getMinutes < 60
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

    Either.fromOption(
      maybeConfig.map{ config =>
        logger.info(s"Got configuration for $previewId - resolving")
        val eventualPreview = Future(Preview(artifact, config, parameters, resources, deploymentTypes))
        previews += previewId -> PreviewResult(eventualPreview)
        previewId
      },
      s"YAML configuration file for ${parameters.build.projectName} ${parameters.build.id} not found"
    )
  }

  def getPreviewResult(id: UUID): Option[PreviewResult] = previews.get(id)
}