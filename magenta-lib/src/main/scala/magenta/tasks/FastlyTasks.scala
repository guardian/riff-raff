package magenta.tasks

import java.util.concurrent.Executors
import com.gu.fastly.api.FastlyApiClient
import magenta._
import magenta.artifact.S3Path
import play.api.libs.json.{JsString, Json, Reads}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import scala.util.{Failure, Success, Try}

case class Version(number: Int, active: Option[Boolean])
object Version {
  implicit val reads: Reads[Version] = Json.reads[Version]
}

case class Vcl(name: String)
object Vcl {
  implicit val reads: Reads[Vcl] = Json.reads[Vcl]
}

case class UpdateFastlyConfig(s3Package: S3Path)(implicit
    val keyRing: KeyRing,
    artifactClient: S3Client,
    parameters: DeployParameters
) extends Task
    with FastlyUtils {

  implicit val ec: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(10))

  private def _execute(
      client: FastlyApiClient,
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Try[Int] = {
    for {
      activeVersionNumber <- Try(
        getActiveVersionNumber(client, resources.reporter, stopFlag)
      )
      nextVersionNumber <- Try(
        clone(activeVersionNumber, client, resources.reporter, stopFlag)
      )
      _ <- Try(
        deleteAllVclFilesFrom(
          nextVersionNumber,
          client,
          resources.reporter,
          stopFlag
        )
      )
      _ <-
        Try(
          uploadNewVclFilesTo(
            nextVersionNumber,
            s3Package,
            client,
            resources.reporter,
            stopFlag
          )
        )
      _ <- Try(
        commentVersion(
          nextVersionNumber,
          client,
          resources.reporter,
          parameters,
          stopFlag
        )
      )
      _ <- Try(
        activateVersion(
          nextVersionNumber,
          client,
          resources.reporter,
          stopFlag
        )
      )
    } yield nextVersionNumber
  }

  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    FastlyApiClientProvider.get(keyRing) match {
      case None =>
        resources.reporter.fail("Failed to fetch Fastly API credentials")
      case Some(client) =>
        _execute(client, resources, stopFlag) match {
          case Success(nextVersionNumber) =>
            resources.reporter
              .info(s"Fastly version $nextVersionNumber is now active")
          case Failure(err) =>
            resources.reporter.fail(
              s"$err. Your API key may be invalid or it may have expired"
            )
        }
    }
  }

  private def deleteAllVclFilesFrom(
      versionNumber: Int,
      client: FastlyApiClient,
      reporter: DeployReporter,
      stopFlag: => Boolean
  ): Unit = {
    stopOnFlag(stopFlag) {
      val vclListResponse = block(client.vclList(versionNumber))
      val vclFilesToDelete =
        Json.parse(vclListResponse.getResponseBody).as[List[Vcl]]
      vclFilesToDelete.foreach { file =>
        reporter.info(s"Deleting ${file.name}")
        block(client.vclDelete(versionNumber, file.name).map(_.getResponseBody))
      }
    }
  }

  private def uploadNewVclFilesTo(
      versionNumber: Int,
      s3Package: S3Path,
      client: FastlyApiClient,
      reporter: DeployReporter,
      stopFlag: => Boolean
  ): Unit = {
    stopOnFlag(stopFlag) {
      s3Package.listAll()(artifactClient).map { obj =>
        if (obj.extension.contains("vcl")) {
          val fileName = obj.relativeTo(s3Package)
          val getObjectRequest = GetObjectRequest
            .builder()
            .bucket(obj.bucket)
            .key(obj.key)
            .build()
          val vcl =
            withResource(artifactClient.getObject(getObjectRequest)) { stream =>
              reporter.info(s"Uploading $fileName")
              scala.io.Source.fromInputStream(stream).mkString
            }
          block(client.vclUpload(versionNumber, vcl, fileName, fileName))
        }
      }
      block(client.vclSetAsMain(versionNumber, "main.vcl"))
    }
  }

  override def activateVersion(
      versionNumber: Int,
      client: FastlyApiClient,
      reporter: DeployReporter,
      stopFlag: => Boolean
  ): Unit = {
    val configIsValid =
      validateNewConfigFor(versionNumber, client, reporter, stopFlag)
    if (configIsValid) {
      block(client.versionActivate(versionNumber))
    } else {
      reporter.fail(s"Error validating Fastly version $versionNumber")
    }
  }

  private def validateNewConfigFor(
      versionNumber: Int,
      client: FastlyApiClient,
      reporter: DeployReporter,
      stopFlag: => Boolean
  ): Boolean = {
    stopOnFlag(stopFlag) {
      reporter.info("Waiting 5 seconds for the VCL to compile")
      Thread.sleep(5000)

      reporter.info(s"Validating new config $versionNumber")
      val response = block(client.versionValidate(versionNumber))
      val jsonString = response.getResponseBody
      val validationResponse = (Json.parse(jsonString) \ "status").toOption
      val isOk = validationResponse.contains(JsString("ok"))
      if (!isOk)
        reporter.warning(
          s"Validation of configuration not OK. API response: $jsonString"
        )
      isOk
    }
  }

  override def description: String =
    "Update configuration of Fastly edge-caching service"
}

object FastlyApiClientProvider {
  def get(keyRing: KeyRing): Option[FastlyApiClient] = {
    keyRing.apiCredentials.get("fastly").collect {
      case credentials: ApiStaticCredentials =>
        val serviceId = credentials.id
        val apiKey = credentials.secret
        FastlyApiClient(apiKey, serviceId)
    }
  }
}
