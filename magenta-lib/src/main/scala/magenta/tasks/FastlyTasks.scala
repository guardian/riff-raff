package magenta.tasks

import magenta.{MessageBroker, KeyRing, Package}
import java.io.{FileInputStream, File}
import java.util.Properties
import moschops.FastlyAPIClient
import org.apache.commons.io.FileUtils
import com.ning.http.client.Response


case class UpdateFastlyConfig(pkg: Package) extends Task {

  // TODO: integrate credentials into riff-raff
  private lazy val credentials = {
    val props = new Properties()
    val stream = new FileInputStream(new File("/home/kchappel/.fastlyapiclientcconfig"))
    props.load(stream)
    stream.close()
    props
  }
  private lazy val serviceId = credentials.getProperty("serviceId")
  private val apiKey = credentials.getProperty("apiKey")

  private lazy val fastlyApiClient: FastlyAPIClient = {
    FastlyAPIClient(apiKey, serviceId)
  }

  def execute(sshCredentials: KeyRing, stopFlag: => Boolean) {

    val vclFiles = pkg.srcDir.listFiles.filter(_.getName.endsWith(".vcl"))
    val vclsToUpdate = vclFiles.foldLeft(Map[String, String]())((map, file) => {
      val name = file.getName
      val vcl = FileUtils.readFileToString(file)
      map + (name -> vcl)
    })

    // TODO: react if can't connect to service
    // TODO: have timeout

    MessageBroker.info("Finding previous config version number...")
    val prevVersion = fastlyApiClient.latestVersionNumber
    MessageBroker.info(prevVersion.toString)

    MessageBroker.info("Cloning previous config...")
    val cloneResponse = fastlyApiClient.versionClone(prevVersion)
    MessageBroker.verbose(cloneResponse.getResponseBody)

    MessageBroker.info("Finding new config version number...")
    val currVersion = fastlyApiClient.latestVersionNumber
    MessageBroker.info(currVersion.toString)

    MessageBroker.info("Updating VCL files...")
    val updateResponses = fastlyApiClient.vclUpdate(vclsToUpdate, currVersion)
    updateResponses.foreach(response => MessageBroker.verbose(response.getResponseBody))

    MessageBroker.info("Activating new config version...")
    val activateResponse = fastlyApiClient.versionActivate(currVersion)
    MessageBroker.verbose(activateResponse.getResponseBody)
  }

  def description: String = "Update configuration of Fastly edge-caching service"

  def verbose: String = description
}
