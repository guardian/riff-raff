package magenta.tasks

import magenta.{MessageBroker, KeyRing, DeploymentPackage}
import java.io.File
import net.liftweb.json._
import com.gu.fastly.api.FastlyApiClient

case class UpdateFastlyConfig(pkg: DeploymentPackage)(implicit val keyRing: KeyRing) extends Task {

  implicit val formats = DefaultFormats

  override def execute(stopFlag: => Boolean) {

    FastlyApiClientProvider.get(keyRing).map {
      client =>

        val activeVersionNumber = getActiveVersionNumber(client, stopFlag)
        val nextVersionNumber = clone(activeVersionNumber, client, stopFlag)
        deleteAllVclFilesFrom(nextVersionNumber, client, stopFlag)
        uploadNewVclFilesTo(nextVersionNumber, pkg.srcDir, client, stopFlag)
        activateVersion(nextVersionNumber, client, stopFlag)
    }
  }

  private def getActiveVersionNumber(client: FastlyApiClient, stopFlag: => Boolean): Int = {
    if (!stopFlag) {
      val versionsJson = client.versionList().get.getResponseBody
      val versions = parse(versionsJson).extract[List[Version]]
      val activeVersion = versions.filter(x => x.active.getOrElse(false) == true)(0)
      MessageBroker.info(s"Current activate version ${activeVersion.number}")
      activeVersion.number
    } else {
      -1
    }
  }

  private def clone(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean): Int = {
    if (!stopFlag) {
      val cloned = client.versionClone(versionNumber).get.getResponseBody
      val clonedVersion = parse(cloned).extract[Version]
      MessageBroker.info(s"Cloned version ${clonedVersion.number}")
      clonedVersion.number
    } else {
      -1
    }
  }

  private def deleteAllVclFilesFrom(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean) = {
    if (!stopFlag) {
      val vclListJson = client.vclList(versionNumber).get.getResponseBody

      val vclFilesToDelete = parse(vclListJson).extract[List[Vcl]]
      vclFilesToDelete.foreach {
        file =>
          MessageBroker.info(s"Deleting ${file.name}")
          client.vclDelete(versionNumber, file.name).get.getResponseBody
      }
    }
  }

  private def uploadNewVclFilesTo(versionNumber: Int, srcDir: File, client: FastlyApiClient, stopFlag: => Boolean) = {
    if (!stopFlag) {
      val vclFilesToUpload = srcDir.listFiles().toList
      vclFilesToUpload.foreach {
        file =>
          if (file.getName.endsWith(".vcl")) {
            MessageBroker.info(s"Uploading ${file.getName}")
            val vcl = scala.io.Source.fromFile(file.getAbsolutePath).mkString
            client.vclUpload(versionNumber, vcl, file.getName, file.getName)
          }
      }
      client.vclSetAsMain(versionNumber, "main.vcl").get
    }
  }

  private def activateVersion(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean) = {
    if (validateNewConfigFor(versionNumber, client, stopFlag)) {
      client.versionActivate(versionNumber).get
      MessageBroker.info(s"Fastly version $versionNumber is now active")
    } else {
      MessageBroker.fail(s"Error validating Fastly version $versionNumber")
    }
  }

  private def validateNewConfigFor(versionNumber: Int, client: FastlyApiClient, stopFlag: => Boolean): Boolean = {
    if (!stopFlag) {

      MessageBroker.info("Waiting 5 seconds for the VCL to compile")
      Thread.sleep(5000)

      MessageBroker.info(s"Validating new config $versionNumber")
      val response = client.versionValidate(versionNumber).get
      val validationResponse = parse(response.getResponseBody) \\ "status"
      validationResponse == JString("ok")
    } else {
      false
    }
  }

  override def description: String = "Update configuration of Fastly edge-caching service"

  override def verbose: String = description

}

case class PurgeFromFastly(urls: Seq[String])(implicit val keyRing: KeyRing) extends Task{
  def verbose = s"Purge $urls from Fastly caches"
  def description = "Purge files from Fastly caches"

  def execute(stopFlag: => Boolean) = {
    FastlyApiClientProvider.get(keyRing).map { client =>
      urls.foreach(url => client.purge(url).get)
    }
  }
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
