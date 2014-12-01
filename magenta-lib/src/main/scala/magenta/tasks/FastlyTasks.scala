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

  override def execute(stopFlag: => Boolean) {

    FastlyApiClientProvider.get(keyRing).map {
      client =>
        val newVersion = Await.result(
          for {
            activeVersionNumber <- getActiveVersionNumber(client, stopFlag)
            nextVersionNumber <- clone(activeVersionNumber, client, stopFlag)
            deleteResult <- deleteAllVclFilesFrom(nextVersionNumber, client, stopFlag)
            uploadResult <- if (deleteResult) uploadNewVclFilesTo(nextVersionNumber, pkg.srcDir, client, stopFlag) else Future.successful(false)
            activateResult <- if (uploadResult) activateVersion(nextVersionNumber, client, stopFlag) else Future.successful(false)
          } yield activateResult
        , 10.minutes)

        MessageBroker.info(s"Fastly version $newVersion is now active")
    }
  }


  def stopOnFlag[T](stopFlag: => Boolean)(block: => Future[T]): Future[T] =
    if (!stopFlag) block else Future.failed(new DeployStoppedException("Deploy manually stopped during UpdateFastlyConfig"))

  private def getActiveVersionNumber(client: FastlyApiClient, stopFlag: => Boolean): Future[Int] = {
    stopOnFlag(stopFlag) {
      for (versionList <- client.versionList()) yield {
        val versions = parse(versionList.getResponseBody).extract[List[Version]]
        val activeVersion = versions.filter(x => x.active.getOrElse(false))(0)
        MessageBroker.info(s"Current active version ${activeVersion.number}")
        activeVersion.number
      }
    }
  }

  private def clone(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean): Future[Int] = {
    stopOnFlag(stopFlag) {
      for (cloned <- client.versionClone(versionNumber)) yield {
        val clonedVersion = parse(cloned.getResponseBody).extract[Version]
        MessageBroker.info(s"Cloned version ${clonedVersion.number}")
        clonedVersion.number
      }
    }
  }

  private def deleteAllVclFilesFrom(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean): Future[Boolean] = {
    stopOnFlag(stopFlag) {
      for {
        vclListResponse <- client.vclList(versionNumber)
        vclFilesToDelete = parse(vclListResponse.getResponseBody).extract[List[Vcl]]
        deleteResponses <- Future.traverse(vclFilesToDelete) {
          file =>
            MessageBroker.info(s"Deleting ${file.name}")
            client.vclDelete(versionNumber, file.name).map(_.getResponseBody)
        }
      } yield {
        MessageBroker.info(s"Deleted ${deleteResponses.size} files")
        true
      }
    }
  }

  private def uploadNewVclFilesTo(versionNumber: Int, srcDir: File, client: FastlyApiClient, stopFlag: => Boolean): Future[Boolean] = {
    stopOnFlag(stopFlag) {
      val vclFilesToUpload = srcDir.listFiles().toList
      Future.traverse(vclFilesToUpload) {
        file =>
          if (file.getName.endsWith(".vcl")) {
            MessageBroker.info(s"Uploading ${file.getName}")
            val vcl = scala.io.Source.fromFile(file.getAbsolutePath).mkString
            client.vclUpload(versionNumber, vcl, file.getName, file.getName)
          } else {
            Future.successful(Nil)
          }
      }.andThen {
        case Success(_) => client.vclSetAsMain(versionNumber, "main.vcl")
        case Failure(e) => MessageBroker.fail("Couldn't set main VCL", e)
      }.map(_ => true)
    }
  }

  private def activateVersion(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean): Future[Int] = {
    for {
      configIsValid <- validateNewConfigFor(versionNumber, client, stopFlag)
      result <-
        if (configIsValid) {
          client.versionActivate(versionNumber).map { r =>
            if (r.getStatusCode == 200) {
              versionNumber
            } else MessageBroker.fail(s"Error activating Fastly version $versionNumber")
          }
        } else {
          MessageBroker.fail(s"Error validating Fastly version $versionNumber")
        }
    } yield result
  }

  private def validateNewConfigFor(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean): Future[Boolean] = {
    stopOnFlag(stopFlag) {
      MessageBroker.info("Waiting 5 seconds for the VCL to compile")
      Thread.sleep(5000)

      MessageBroker.info(s"Validating new config $versionNumber")
      for { response <- client.versionValidate(versionNumber) } yield {
        val validationResponse = parse(response.getResponseBody) \\ "status"
        validationResponse == JString("ok")
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
