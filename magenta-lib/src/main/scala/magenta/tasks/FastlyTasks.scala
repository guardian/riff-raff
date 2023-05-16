package magenta.tasks

import java.util.concurrent.Executors
import com.gu.fastly.api.FastlyApiClient
import magenta._
import magenta.artifact.S3Path
import play.api.libs.json.{JsResultException, JsString, Json, Reads}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

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

  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    FastlyApiClientProvider.get(keyRing) match {
      case None =>
        resources.reporter.fail("Failed to fetch Fastly API credentials")
      case Some(client) =>
        try {
          val activeVersionNumber =
            getActiveVersionNumber(client, resources.reporter, stopFlag)
          val nextVersionNumber =
            clone(activeVersionNumber, client, resources.reporter, stopFlag)

          deleteAllVclFilesFrom(
            nextVersionNumber,
            client,
            resources.reporter,
            stopFlag
          )

          uploadNewVclFilesTo(
            nextVersionNumber,
            s3Package,
            client,
            resources.reporter,
            stopFlag
          )

          commentVersion(
            nextVersionNumber,
            client,
            resources.reporter,
            parameters,
            stopFlag
          )

          activateVersion(
            nextVersionNumber,
            client,
            resources.reporter,
            stopFlag
          )

          resources.reporter
            .info(s"Fastly version $nextVersionNumber is now active")
        } catch {
          case err: Exception =>
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
