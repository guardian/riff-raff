package magenta.tasks

import magenta.{MessageBroker, KeyRing, Package}
import java.io.{FileInputStream, File}
import java.util.Properties
import moschops.FastlyAPIClient
import org.apache.commons.io.FileUtils
import com.ning.http.client.{AsyncHttpClientConfig, Response}


case class UpdateFastlyConfig(pkg: Package) extends Task {

  // TODO: integrate credentials into riff-raff
  private val credentials = {
    val props = new Properties()
    val stream = new FileInputStream(new File("/home/kchappel/.fastlyapiclientcconfig"))
    props.load(stream)
    stream.close()
    props
  }
  private val serviceId = credentials.getProperty("serviceId")
  private val apiKey = credentials.getProperty("apiKey")

  private val fastlyApiClient: FastlyAPIClient = {
    val config = new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(30000).build()
    FastlyAPIClient(apiKey, serviceId, Some(config))
  }

  override def execute(sshCredentials: KeyRing, stopFlag: => Boolean) {

    val vclFiles = pkg.srcDir.listFiles.filter(_.getName.endsWith(".vcl"))
    val vclsToUpdate = vclFiles.foldLeft(Map[String, String]())((map, file) => {
      val name = file.getName
      val vcl = FileUtils.readFileToString(file)
      map + (name -> vcl)
    })

    def fails(response: Response): Boolean = {
      if (response.getStatusCode == 200) {
        MessageBroker.verbose(response.getResponseBody)
        true
      }
      MessageBroker.fail(response.getResponseBody)
      false
    }

    var prevVersion = -1
    if (!stopFlag) {
      MessageBroker.info("Finding previous config version number...")
      prevVersion = fastlyApiClient.latestVersionNumber
      MessageBroker.info(prevVersion.toString)
      true
    }

    if (!stopFlag) {
      MessageBroker.info("Cloning previous config...")
      if (fails(fastlyApiClient.versionClone(prevVersion))) return
      true
    }

    var currVersion = -1
    if (!stopFlag) {
      MessageBroker.info("Finding new config version number...")
      currVersion = fastlyApiClient.latestVersionNumber
      MessageBroker.info(currVersion.toString)
      true
    }

    if (!stopFlag) {
      MessageBroker.info("Updating VCL files...")
      val updateResponses = fastlyApiClient.vclUpdate(vclsToUpdate, currVersion)
      updateResponses.foreach {
        response =>
          if (fails(response)) return
      }
      true
    }

    if (!stopFlag) {
      MessageBroker.info("Activating new config version...")
      if (fails(fastlyApiClient.versionActivate(currVersion))) return
      true
    }

  }

  override def description: String = "Update configuration of Fastly edge-caching service"

  override def verbose: String = description

}
