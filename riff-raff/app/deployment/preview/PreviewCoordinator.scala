package deployment.preview

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import cats.syntax.either._
import com.gu.management.Loggable
import conf.Config
import magenta.artifact.{S3Error, S3YamlArtifact}
import magenta.deployment_type.DeploymentType
import magenta.{DeployParameters, DeployReporter, DeploymentResources}
import resources.PrismLookup

import scala.collection.JavaConverters._
import scala.collection.concurrent.{Map => ConcurrentMap}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PreviewCoordinator(config: Config, prismLookup: PrismLookup, deploymentTypes: Seq[DeploymentType]) extends Loggable {
  private val previews: ConcurrentMap[UUID, PreviewResult] = new ConcurrentHashMap[UUID, PreviewResult]().asScala

  def cleanupPreviews() {
    previews.retain{(uuid, result) =>
      !result.future.isCompleted || result.duration.toStandardMinutes.getMinutes < 60
    }
  }

  def startPreview(parameters: DeployParameters): Either[S3Error, UUID] = {
    cleanupPreviews()

    val previewId = UUID.randomUUID()
    logger.info(s"Starting preview for $previewId")
    val muteLogger = DeployReporter.rootReporterFor(previewId, parameters, publishMessages = false)
    val resources = DeploymentResources(muteLogger, prismLookup, config.artifact.aws.client, config.credentials.stsClient)
    val artifact = S3YamlArtifact.apply(parameters.build, config.artifact.aws.bucketName)
    val maybeConfig = artifact.deployObject.fetchContentAsString()(resources.artifactClient)

    maybeConfig.map(config => {
      logger.info(s"Got configuration for $previewId - resolving")
      val eventualPreview = Future(Preview(artifact, config, parameters, resources, deploymentTypes))
      previews += previewId -> PreviewResult(eventualPreview)
      previewId
    })
  }

  def getPreviewResult(id: UUID): Option[PreviewResult] = previews.get(id)
}