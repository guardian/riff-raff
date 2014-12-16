package magenta.tasks

import java.util.concurrent.Executors

import magenta.{DeployStoppedException, MessageBroker, KeyRing, DeploymentPackage}
import java.io.File
import org.json4s._
import org.json4s.native.JsonMethods._
import com.gu.fastly.api.FastlyApiClient

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

case class UpdateFastlyConfig(pkg: DeploymentPackage)(implicit val keyRing: KeyRing) extends Task {

  implicit val formats = DefaultFormats

  implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(10))

  // No, I'm not happy about this, but it gets things working until we can make a larger change
  def block[T](f: => Future[T]) = Await.result(f, 1.minute)

  override def execute(stopFlag: => Boolean) {

    FastlyApiClientProvider.get(keyRing).map {
      client => {
        val activeVersionNumber = getActiveVersionNumber(client, stopFlag)
        val nextVersionNumber = clone(activeVersionNumber, client, stopFlag)

        deleteAllVclFilesFrom(nextVersionNumber, client, stopFlag)

        uploadNewVclFilesTo(nextVersionNumber, pkg.srcDir, client, stopFlag)
        activateVersion(nextVersionNumber, client, stopFlag)

        MessageBroker.info(s"Fastly version $nextVersionNumber is now active")
      }
    }
  }


  def stopOnFlag[T](stopFlag: => Boolean)(block: => T): T =
    if (!stopFlag) block else throw new DeployStoppedException("Deploy manually stopped during UpdateFastlyConfig")

  private def getActiveVersionNumber(client: FastlyApiClient, stopFlag: => Boolean): Int = {
    stopOnFlag(stopFlag) {
      val versionList = block(client.versionList())
      val versions = parse(versionList.getResponseBody).extract[List[Version]]
      val activeVersion = versions.filter(x => x.active.getOrElse(false))(0)
      MessageBroker.info(s"Current active version ${activeVersion.number}")
      activeVersion.number
    }
  }

  private def clone(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean): Int = {
    stopOnFlag(stopFlag) {
      val cloned = block(client.versionClone(versionNumber))
      val clonedVersion = parse(cloned.getResponseBody).extract[Version]
      MessageBroker.info(s"Cloned version ${clonedVersion.number}")
      clonedVersion.number
    }
  }

  private def deleteAllVclFilesFrom(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean): Unit = {
    stopOnFlag(stopFlag) {
      val vclListResponse = block(client.vclList(versionNumber))
      val vclFilesToDelete = parse(vclListResponse.getResponseBody).extract[List[Vcl]]
      vclFilesToDelete.foreach { file =>
        MessageBroker.info(s"Deleting ${file.name}")
        block(client.vclDelete(versionNumber, file.name).map(_.getResponseBody))
      }
    }
  }

  private def uploadNewVclFilesTo(versionNumber: Int, srcDir: File, client: FastlyApiClient, stopFlag: => Boolean): Unit = {
    stopOnFlag(stopFlag) {
      val vclFilesToUpload = srcDir.listFiles().toList
      vclFilesToUpload.foreach { file =>
        if (file.getName.endsWith(".vcl")) {
          MessageBroker.info(s"Uploading ${file.getName}")
          val vcl = scala.io.Source.fromFile(file.getAbsolutePath).mkString
          block(client.vclUpload(versionNumber, vcl, file.getName, file.getName))
        }
      }
      block(client.vclSetAsMain(versionNumber, "main.vcl"))
    }
  }

  private def activateVersion(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean): Unit = {
    val configIsValid = validateNewConfigFor(versionNumber, client, stopFlag)
    if (configIsValid) {
      block(client.versionActivate(versionNumber))
    } else {
      MessageBroker.fail(s"Error validating Fastly version $versionNumber")
    }
  }

  private def validateNewConfigFor(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean): Boolean = {
    stopOnFlag(stopFlag) {
      MessageBroker.info("Waiting 5 seconds for the VCL to compile")
      Thread.sleep(5000)

      MessageBroker.info(s"Validating new config $versionNumber")
      val response = block(client.versionValidate(versionNumber))
      val validationResponse = parse(response.getResponseBody) \\ "status"
      validationResponse == JString("ok")
    }
  }

  override def description: String = "Update configuration of Fastly edge-caching service"

  override def verbose: String = description
}

case class Version(number: Int, active: Option[Boolean])

case class Vcl(name: String)

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
