package magenta.tasks

import java.util.concurrent.Executors
import com.gu.fastly.api.FastlyApiClient
import magenta._
import magenta.artifact.S3Path
import play.api.libs.json.Json
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest

import java.nio.file.Files
import scala.concurrent.duration._
import scala.concurrent.{
  Await,
  ExecutionContext,
  ExecutionContextExecutorService,
  Future
}

case class UpdateFastlyPackage(s3Package: S3Path)(implicit
    val keyRing: KeyRing,
    artifactClient: S3Client
) extends Task {

  implicit val ec: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(10))

  def block[T](f: => Future[T]): T = Await.result(f, 1.minute)

  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    FastlyApiClientProvider.get(keyRing).foreach { client =>
      val activeVersionNumber =
        getActiveVersionNumber(client, resources.reporter, stopFlag)
      val nextVersionNumber =
        clone(activeVersionNumber, client, resources.reporter, stopFlag)

      uploadPackageTo(
        nextVersionNumber,
        s3Package,
        client,
        resources.reporter,
        stopFlag
      )

      activateVersion(nextVersionNumber, client, resources.reporter)

      resources.reporter
        .info(s"Fastly version $nextVersionNumber is now active")
    }
  }

  def stopOnFlag[T](stopFlag: => Boolean)(block: => T): T =
    if (!stopFlag) block
    else
      throw DeployStoppedException(
        "Deploy manually stopped during UpdateFastlyConfig"
      )

  private def getActiveVersionNumber(
      client: FastlyApiClient,
      reporter: DeployReporter,
      stopFlag: => Boolean
  ): Int = {
    stopOnFlag(stopFlag) {
      val versionList = block(client.versionList())
      val versions = Json.parse(versionList.getResponseBody).as[List[Version]]
      val activeVersion = versions.filter(x => x.active.getOrElse(false)).head
      reporter.info(s"Current active version ${activeVersion.number}")
      activeVersion.number
    }
  }

  private def clone(
      versionNumber: Int,
      client: FastlyApiClient,
      reporter: DeployReporter,
      stopFlag: => Boolean
  ): Int = {
    stopOnFlag(stopFlag) {
      val cloned = block(client.versionClone(versionNumber))
      val clonedVersion = Json.parse(cloned.getResponseBody).as[Version]
      reporter.info(s"Cloned version ${clonedVersion.number}")
      clonedVersion.number
    }
  }

  private def uploadPackageTo(
      versionNumber: Int,
      s3Package: S3Path,
      client: FastlyApiClient,
      reporter: DeployReporter,
      stopFlag: => Boolean
  ): Unit = {
    stopOnFlag(stopFlag) {
      s3Package.listAll()(artifactClient).map { obj =>
        if (obj.extension.contains("tar.gz")) {
          val fileName = obj.relativeTo(s3Package)
          val getObjectRequest = GetObjectRequest
            .builder()
            .bucket(obj.bucket)
            .key(obj.key)
            .build()
          val `package` =
            withResource(artifactClient.getObject(getObjectRequest)) { stream =>
              reporter.info(s"Uploading $fileName")
              val bytes =
                scala.io.Source.fromInputStream(stream).mkString.getBytes
              Files
                .write(Files.createTempFile("temp-package", "tar.gz"), bytes)
                .toFile
            }
          block(
            client.packageUpload(client.serviceId, versionNumber, `package`)
          )
        }
      }
    }
  }

  private def activateVersion(
      versionNumber: Int,
      client: FastlyApiClient,
      reporter: DeployReporter
  ): Unit = {
    reporter.info(
      s"Activating Fastly service ${client.serviceId} - version $versionNumber"
    )
    block(client.versionActivate(versionNumber))
  }

  override def description: String =
    "Upload a Compute@Edge package"
}
