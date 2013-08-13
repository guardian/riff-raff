package magenta.tasks

import magenta.{MessageBroker, KeyRing, Package}
import com.gu.FastlyAPIClient
import java.io.File
import net.liftweb.json._

case class UpdateFastlyConfig(pkg: Package)
  extends Task {

  override def execute(keyRing: KeyRing, stopFlag: => Boolean) {

    FastlyApiClientProvider.get(keyRing).map { client =>

      implicit val formats = DefaultFormats

      // helper methods
      def getActiveVersionNumber: Int = {
        if (!stopFlag) {
          val versionsJson = client.versions().get.getResponseBody
          val versions = parse(versionsJson).extract[List[Version]]
          val activeVersion = versions.filter(x => x.active.getOrElse(false) == true)(0)
          MessageBroker.info("Current activate version %d".format(activeVersion.number))
          activeVersion.number
        } else {
          0
        }
      }

      def clone(versionNumber: Int): Int = {
        if (!stopFlag) {
          val cloned = client.versionClone(versionNumber).get.getResponseBody
          val clonedVersion = parse(cloned).extract[Version]
          MessageBroker.info("Cloned version %d".format(clonedVersion.number))
          clonedVersion.number
        } else {
          0
        }
      }

      def deleteAllVclFilesFrom(versionNumber: Int) = {
        if (!stopFlag) {
          val vclListJson = client.vclList(versionNumber).get.getResponseBody
          val vclFilesToDelete = parse(vclListJson).extract[List[Vcl]]
          vclFilesToDelete.foreach {
            file =>
              MessageBroker.info("Deleting %s".format(file.name))
              client.vclDelete(versionNumber, file.name).get.getResponseBody
          }
        }
      }

      def uploadNewVclFilesTo(srcDir: File, versionNumber: Int) = {
        if (!stopFlag) {
          val vclFilesToUpload = srcDir.listFiles().toList
          vclFilesToUpload.foreach {
            file =>
              if (file.getName.endsWith(".vcl")) {
                MessageBroker.info("Uploading %s".format(file.getName))
                val vcl = scala.io.Source.fromFile(file.getAbsolutePath).mkString
                client.vclUpload(versionNumber, vcl, file.getName, file.getName)
              }
          }
          client.vclSetAsMain(versionNumber, "main.vcl").get
        }
      }

      def activateConfig(versionNumber: Int) = {
        if (validateNewConfigFor(versionNumber)) {
          client.versionActivate(versionNumber).get
          MessageBroker.info("Fastly version %s is now active".format(versionNumber))
        } else {
          MessageBroker.info("Error validating Fastly version %s".format(versionNumber))
        }
      }

      def validateNewConfigFor(versionNumber: Int): Boolean = {
        if (!stopFlag) {
          MessageBroker.info("Validating new congif %s".format(versionNumber))
          val response = client.versionValidate(versionNumber).get
          val validationResponse = parse(response.getResponseBody) \\ "status"
          validationResponse == JString("ok")
        } else {
          false
        }
      }

      case class Version(number: Int, active: Option[Boolean])
      case class Vcl(name: String)

      // Main flow
      val activeVersionNumber = getActiveVersionNumber
      val nextVersionNumber = clone(activeVersionNumber)
      deleteAllVclFilesFrom(nextVersionNumber)
      uploadNewVclFilesTo(pkg.srcDir, nextVersionNumber)
      activateConfig(nextVersionNumber)

    }
  }

  override def description: String = "Update configuration of Fastly edge-caching service"

  override def verbose: String = description

}

object FastlyApiClientProvider {
  private var fastlApiClient: Option[FastlyAPIClient] = None

  def get(keyRing: KeyRing): Option[FastlyAPIClient] = {

    if (this.fastlApiClient.isEmpty) {

      keyRing.apiCredentials.get("fastly").map {
        credentials => {
          val serviceId = credentials.id
          val apiKey = credentials.secret
          this.fastlApiClient = Option(new FastlyAPIClient(apiKey, serviceId))
        }
      }

    }
    this.fastlApiClient
  }
}
