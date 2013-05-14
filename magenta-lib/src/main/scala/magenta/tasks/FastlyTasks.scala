package magenta.tasks

import magenta.{MessageBroker, KeyRing, Package}
import org.apache.commons.io.FileUtils
import com.ning.http.client.Response
import scala.collection.JavaConversions._
import net.liftweb.json._
import com.gu.FastlyAPIClient


case class UpdateFastlyConfig(pkg: Package,
                              fastlyApiClientBuilder: FastlyApiClientBuilder = new DefaultFastlyApiClientBuilder)
  extends Task {

  override def execute(keyRing: KeyRing, stopFlag: => Boolean) {

    val clients: List[FastlyAPIClient] = for (crdentials <- keyRing.apiCredentials if (crdentials.service) == "fastly") yield {
      val serviceId = crdentials.id
      val apiKey = crdentials.secret
      fastlyApiClientBuilder.build(apiKey, serviceId)
    }

    clients.map(fastlyApiClient => {

      val vclFiles = pkg.srcDir.listFiles.filter(_.getName.endsWith(".vcl"))
      val vclsToAddOrUpdate = vclFiles.foldLeft(Map[String, String]())((map, file) => {
        val vclName = file.getName
        val vclContent = FileUtils.readFileToString(file)
        map + (vclName -> vclContent)
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
        prevVersion = fastlyApiClient.latestVersionNumber()
        MessageBroker.info(prevVersion.toString)
      }

      if (!stopFlag) {
        MessageBroker.info("Cloning previous config...")
        if (fails(fastlyApiClient.versionClone(prevVersion).get)) return
      }

      var currVersion = -1
      if (!stopFlag) {
        MessageBroker.info("Finding new config version number...")
        currVersion = fastlyApiClient.latestVersionNumber()
        MessageBroker.info(currVersion.toString)
      }

      if (!stopFlag) {
        MessageBroker.info("Updating VCL files...")

        val vclListResponse = fastlyApiClient.vclList(currVersion).get
        val vclListJson = parse(vclListResponse.getResponseBody)
        val JArray(nameFields) = vclListJson \ "name"
        val currentVcls = nameFields collect {
          case nameField: JField => nameField.values._2
        }

        val vclsToAdd = vclsToAddOrUpdate.filterNot {
          case (vclName, vclContent) => currentVcls.contains(vclName)
        }
        vclsToAdd.foreach {
          case (vclName, vclContent) =>
            if (fails(fastlyApiClient.vclUpload(vclContent, vclContent, vclName, currVersion).get)) return
        }

        val vclsToUpdate = vclsToAddOrUpdate.filter {
          case (vclName, vclContent) => currentVcls.contains(vclName)
        }
        val updateResponses = fastlyApiClient.vclUpdate(vclsToUpdate, currVersion)
        updateResponses.foreach {
          response =>
            if (fails(response.get)) return
        }
      }

      if (!stopFlag) {
        MessageBroker.info("Activating new config version...")
        if (fails(fastlyApiClient.versionActivate(currVersion).get)) return
      }


    })


  }

  override def description: String = "Update configuration of Fastly edge-caching service"

  override def verbose: String = description

}

trait FastlyApiClientBuilder {

  def build(apiKey: String, serviceId: String): FastlyAPIClient
}

class DefaultFastlyApiClientBuilder extends FastlyApiClientBuilder {

  override def build(apiKey: String, serviceId: String) = FastlyAPIClient(apiKey, serviceId)
}
