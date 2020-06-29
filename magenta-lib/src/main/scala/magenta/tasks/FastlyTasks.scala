package magenta.tasks

import java.util.concurrent.Executors

import com.gu.fastly.api.FastlyApiClient
import magenta._
import magenta.artifact.S3Path
import play.api.libs.json.{JsString, Json}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

case class Version(number: Int, active: Option[Boolean])
object Version {
  implicit val reads = Json.reads[Version]
}

case class Vcl(name: String)
object Vcl {
  implicit val reads = Json.reads[Vcl]
}

case class UpdateFastlyConfig(s3Package: S3Path)(implicit val keyRing: KeyRing, artifactClient: S3Client) extends Task {

  implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(10))

  // No, I'm not happy about this, but it gets things working until we can make a larger change
  def block[T](f: => Future[T]) = Await.result(f, 1.minute)

  override def execute(resources: DeploymentResources, stopFlag: => Boolean) {
    FastlyApiClientProvider.get(keyRing).foreach { client =>
      val activeVersionNumber = getActiveVersionNumber(client, resources.reporter, stopFlag)
      val nextVersionNumber = clone(activeVersionNumber, client, resources.reporter, stopFlag)

      deleteAllVclFilesFrom(nextVersionNumber, client, resources.reporter, stopFlag)

      uploadNewVclFilesTo(nextVersionNumber, s3Package, client, resources.reporter, stopFlag)
      activateVersion(nextVersionNumber, client, resources.reporter, stopFlag)

      resources.reporter.info(s"Fastly version $nextVersionNumber is now active")
    }
  }


  def stopOnFlag[T](stopFlag: => Boolean)(block: => T): T =
    if (!stopFlag) block else throw new DeployStoppedException("Deploy manually stopped during UpdateFastlyConfig")

  private def getActiveVersionNumber(client: FastlyApiClient, reporter: DeployReporter, stopFlag: => Boolean): Int = {
    stopOnFlag(stopFlag) {
      val versionList = block(client.versionList())
      val versions = Json.parse(versionList.getResponseBody).as[List[Version]]
      val activeVersion = versions.filter(x => x.active.getOrElse(false))(0)
      reporter.info(s"Current active version ${activeVersion.number}")
      activeVersion.number
    }
  }

  private def clone(versionNumber: Int, client: FastlyApiClient, reporter: DeployReporter, stopFlag: => Boolean): Int = {
    stopOnFlag(stopFlag) {
      val cloned = block(client.versionClone(versionNumber))
      val clonedVersion = Json.parse(cloned.getResponseBody).as[Version]
      reporter.info(s"Cloned version ${clonedVersion.number}")
      clonedVersion.number
    }
  }

  private def deleteAllVclFilesFrom(versionNumber: Int, client: FastlyApiClient, reporter: DeployReporter, stopFlag: => Boolean): Unit = {
    stopOnFlag(stopFlag) {
      val vclListResponse = block(client.vclList(versionNumber))
      val vclFilesToDelete = Json.parse(vclListResponse.getResponseBody).as[List[Vcl]]
      vclFilesToDelete.foreach { file =>
        reporter.info(s"Deleting ${file.name}")
        block(client.vclDelete(versionNumber, file.name).map(_.getResponseBody))
      }
    }
  }

  private def uploadNewVclFilesTo(versionNumber: Int, s3Package: S3Path, client: FastlyApiClient, reporter: DeployReporter, stopFlag: => Boolean): Unit = {
    stopOnFlag(stopFlag) {
      s3Package.listAll()(artifactClient).map { obj =>
        if (obj.extension.contains("vcl")) {
          val fileName = obj.relativeTo(s3Package)
          val getObjectRequest = GetObjectRequest.builder()
            .bucket(obj.bucket)
            .key(obj.key)
            .build()
          val vcl = withResource(artifactClient.getObject(getObjectRequest)) { stream =>
            reporter.info(s"Uploading $fileName")
            scala.io.Source.fromInputStream(stream).mkString
          }
          block(client.vclUpload(versionNumber, vcl, fileName, fileName))
        }
      }
      block(client.vclSetAsMain(versionNumber, "main.vcl"))
    }
  }

  private def activateVersion(versionNumber: Int, client: FastlyApiClient, reporter: DeployReporter, stopFlag: => Boolean): Unit = {
    val configIsValid = validateNewConfigFor(versionNumber, client, reporter, stopFlag)
    if (configIsValid) {
      block(client.versionActivate(versionNumber))
    } else {
      reporter.fail(s"Error validating Fastly version $versionNumber")
    }
  }

  private def validateNewConfigFor(versionNumber: Int, client: FastlyApiClient, reporter: DeployReporter, stopFlag: => Boolean): Boolean = {
    stopOnFlag(stopFlag) {
      reporter.info("Waiting 5 seconds for the VCL to compile")
      Thread.sleep(5000)

      reporter.info(s"Validating new config $versionNumber")
      val response = block(client.versionValidate(versionNumber))
      val jsonString = response.getResponseBody
      val validationResponse = (Json.parse(jsonString) \ "status").toOption
      val isOk = validationResponse.contains(JsString("ok"))
      if (!isOk) reporter.warning(s"Validation of configuration not OK. API response: $jsonString")
      isOk
    }
  }

  override def description: String = "Update configuration of Fastly edge-caching service"
}

object FastlyApiClientProvider {

  private var fastlyApiClients = Map[String, FastlyApiClient]()

  def get(keyRing: KeyRing): Option[FastlyApiClient] = {

    keyRing.apiCredentials.get("fastly").map { credentials =>
        val serviceId = credentials.id
        val apiKey = credentials.secret

        if (fastlyApiClients.get(serviceId).isEmpty) {
          this.fastlyApiClients += (serviceId -> new FastlyApiClient(apiKey, serviceId))
        }
        return fastlyApiClients.get(serviceId)
    }

    None
  }
}
