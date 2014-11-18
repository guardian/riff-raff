package magenta.tasks

import java.util.concurrent.Executors

import magenta.{MessageBroker, KeyRing, DeploymentPackage}
import java.io.File
import org.json4s._
import org.json4s.native.JsonMethods._
import com.gu.fastly.api.FastlyApiClient

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.async.Async.{async, await}
import scala.util.Success

case class UpdateFastlyConfig(pkg: DeploymentPackage)(implicit val keyRing: KeyRing) extends Task {

  implicit val formats = DefaultFormats

  implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(10))

  override def execute(stopFlag: => Boolean) {

    FastlyApiClientProvider.get(keyRing).map {
      client =>
        async {
          val activeVersionNumber = await(getActiveVersionNumber(client, stopFlag))
          val nextVersionNumber = await(clone(activeVersionNumber, client, stopFlag))
          deleteAllVclFilesFrom(nextVersionNumber, client, stopFlag).andThen {
            case Success(_) => uploadNewVclFilesTo(nextVersionNumber, pkg.srcDir, client, stopFlag).andThen {
              case Success(_)  => activateVersion(nextVersionNumber, client, stopFlag)
            }
          }
        }
    }
  }

  private def getActiveVersionNumber(client: FastlyApiClient, stopFlag: => Boolean): Future[Int] = {
    async {
      if (!stopFlag) {
        val versionsJson = await(client.versionList()).getResponseBody
        val versions = parse(versionsJson).extract[List[Version]]
        val activeVersion = versions.filter(x => x.active.getOrElse(false) == true)(0)
        MessageBroker.info(s"Current activate version ${activeVersion.number}")
        activeVersion.number
      } else {
        -1
      }
    }
  }

  private def clone(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean): Future[Int] = {
    async {
      if (!stopFlag) {
        val cloned = await(client.versionClone(versionNumber)).getResponseBody
        val clonedVersion = parse(cloned).extract[Version]
        MessageBroker.info(s"Cloned version ${clonedVersion.number}")
        clonedVersion.number
      } else {
        -1
      }
    }
  }

  private def deleteAllVclFilesFrom(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean) = {
    async {
      if (!stopFlag) {
        val vclListJson = await(client.vclList(versionNumber)).getResponseBody

        val vclFilesToDelete = parse(vclListJson).extract[List[Vcl]]
        Future.traverse(vclFilesToDelete) {
          file =>
            MessageBroker.info(s"Deleting ${file.name}")
            client.vclDelete(versionNumber, file.name).map(_.getResponseBody)
        }
      }
    }
  }

  private def uploadNewVclFilesTo(versionNumber: Int, srcDir: File, client: FastlyApiClient, stopFlag: => Boolean) = {
    async {
      if (!stopFlag) {
        val vclFilesToUpload = srcDir.listFiles().toList
        Future.traverse(vclFilesToUpload) {
          file =>
            if (file.getName.endsWith(".vcl")) {
              MessageBroker.info(s"Uploading ${file.getName}")
              val vcl = scala.io.Source.fromFile(file.getAbsolutePath).mkString
              client.vclUpload(versionNumber, vcl, file.getName, file.getName)
            } else {
              Future.successful(())
            }
        }.andThen {
          case Success(_) => client.vclSetAsMain(versionNumber, "main.vcl")
        }
      }
    }
  }

  private def activateVersion(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean) = {
    async {
      if (await(validateNewConfigFor(versionNumber, client, stopFlag))) {
        await(client.versionActivate(versionNumber))
        MessageBroker.info(s"Fastly version $versionNumber is now active")
      } else {
        MessageBroker.fail(s"Error validating Fastly version $versionNumber")
      }
    }
  }

  private def validateNewConfigFor(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean): Future[Boolean] = {
    async {
      if (!stopFlag) {

        MessageBroker.info("Waiting 5 seconds for the VCL to compile")
        Thread.sleep(5000)

        MessageBroker.info(s"Validating new config $versionNumber")
        val response = await(client.versionValidate(versionNumber))
        val validationResponse = parse(response.getResponseBody) \\ "status"
        validationResponse == JString("ok")
      } else {
        false
      }
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
