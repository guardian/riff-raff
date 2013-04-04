package magenta.tasks

import magenta.{MessageBroker, KeyRing, Package}
import moschops.FastlyAPIClient
import org.apache.commons.io.FileUtils
import com.ning.http.client.Response
import scala.collection.JavaConversions._


case class UpdateFastlyConfig(pkg: Package) extends Task {

  override def execute(keyRing: KeyRing, stopFlag: => Boolean) {

    val fastlyCredentials = keyRing.apiCredentials.find(_.service == "fastly")
    if (fastlyCredentials.isEmpty) {
      MessageBroker.fail("No Fastly credentials available")
      return
    }
    val serviceId = fastlyCredentials.get.id
    val apiKey = fastlyCredentials.get.secret
    val fastlyApiClient = FastlyAPIClient(apiKey, serviceId)

    val vclFiles = pkg.srcDir.listFiles.filter(_.getName.endsWith(".vcl"))
    val vclsToUpdate = vclFiles.foldLeft(Map[String, String]())((map, file) => {
      val name = file.getName
      val vcl = FileUtils.readFileToString(file)
      map + (name -> vcl)
    })

    def fails(response: Response): Boolean = {
      if (response.getStatusCode == 200) {
        MessageBroker.verbose(response.getResponseBody)
        return false
      }

      val headerDump = mapAsScalaMap(response.getHeaders) map {
        case (key, value) => "%s=%s" format(key, value)
      } mkString ("; ")
      val statusDump = "Status code: %s; Headers: %s; Response body: %s"
        .format(response.getStatusCode, headerDump, response.getResponseBody)
      MessageBroker.fail(statusDump)
      true
    }

    var prevVersion = -1
    if (!stopFlag) {
      MessageBroker.info("Finding previous config version number...")
      prevVersion = fastlyApiClient.latestVersionNumber
      MessageBroker.info(prevVersion.toString)
    }

    if (!stopFlag) {
      MessageBroker.info("Cloning previous config...")
      if (fails(fastlyApiClient.versionClone(prevVersion))) return
    }

    var currVersion = -1
    if (!stopFlag) {
      MessageBroker.info("Finding new config version number...")
      currVersion = fastlyApiClient.latestVersionNumber
      MessageBroker.info(currVersion.toString)
    }

    if (!stopFlag) {
      MessageBroker.info("Updating VCL files...")
      val updateResponses = fastlyApiClient.vclUpdate(vclsToUpdate, currVersion)
      updateResponses.foreach {
        response =>
          if (fails(response)) return
      }
    }

    if (!stopFlag) {
      MessageBroker.info("Activating new config version...")
      if (fails(fastlyApiClient.versionActivate(currVersion))) return
    }

  }

  override def description: String = "Update configuration of Fastly edge-caching service"

  override def verbose: String = description

}
