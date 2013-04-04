package deployment

import magenta.json.DeployInfoJsonReader
import magenta._
import akka.actor.ActorSystem
import akka.util.duration._
import controllers.Logging
import magenta.App
import conf.{DeployInfoMode, Configuration}
import utils.ScheduledAgent
import java.io.{FileInputStream, FileNotFoundException, File}
import java.net.{URLConnection, URL, URLStreamHandler}
import io.Source
import lifecycle.LifecycleWithoutApp
import java.util.Properties

object DeployInfoManager extends LifecycleWithoutApp with Logging {
  private val classpathHandler = new URLStreamHandler {
    val classloader = getClass.getClassLoader
    override def openConnection(u: URL): URLConnection = {
      val resourceURL = classloader.getResource(u.getPath)
      if (resourceURL == null)
        throw new FileNotFoundException("%s not found on classpath" format u.getPath)
      resourceURL.openConnection()
    }
  }

  private def getDeployInfo = {
    import sys.process._
    log.info("Populating deployinfo hosts...")
    val deployInfoJson: String = Configuration.deployinfo.mode match {
      case DeployInfoMode.Execute =>
        if (new File(Configuration.deployinfo.location).exists)
          Configuration.deployinfo.location.!!
        else {
          log.warn("No file found at '%s', defaulting to empty DeployInfo" format (Configuration.deployinfo.location))
          ""
        }
      case DeployInfoMode.URL =>
        val url = Configuration.deployinfo.location match {
          case classPathLocation if classPathLocation.startsWith("classpath:") => new URL(null, classPathLocation, classpathHandler)
          case otherURL => new URL(otherURL)
        }
        log.info("URL: %s" format url)
        Source.fromURL(url).getLines.mkString
    }

    val deployInfo = DeployInfoJsonReader.parse(deployInfoJson)

    log.info("Successfully retrieved deployinfo (%d hosts and %d data found)" format (
      deployInfo.hosts.size, deployInfo.data.values.map(_.size).fold(0)(_+_)))

    deployInfo
  }

  val system = ActorSystem("deploy")
  var agent: Option[ScheduledAgent[DeployInfo]] = None

  def init() {
    agent = Some(ScheduledAgent[DeployInfo](0 seconds, 1 minute, DeployInfo())(_ => getDeployInfo))
  }

  def deployInfo = agent.map(_()).getOrElse(DeployInfo())

  def stageList = deployInfo.knownHostStages.sorted(conf.Configuration.stages.ordering)
  def hostList = deployInfo.hosts
  def dataList = deployInfo.data

  def credentials(stage: String, apps: Set[App]): List[Credentials] = {
    val s3Credentials = apps.toList.flatMap(app => deployInfo.firstMatchingData("aws-keys", app, stage)).map(k => Configuration.s3.credentials(k.value)).distinct

    // TODO: get credentials from deployment info
    //    val fastlyCredentials = apps.headOption.flatMap {
    //      app => {
    //        val serviceIdData = deployInfo.firstMatchingData("fastly-service-id", app, stage)
    //        val apiKeyData = deployInfo.firstMatchingData("fastly-api-key", app, stage)
    //        (serviceIdData, apiKeyData) match {
    //          case (Some(serviceId), Some(apiKey)) => Some(FastlyCredentials(serviceId.value, apiKey.value))
    //          case _ => None
    //        }
    //      }
    //    }
    val tmpProps = {
      val props = new Properties()
      val stream = new FileInputStream(new File("/home/kchappel/.fastlyapiclientcconfig"))
      props.load(stream)
      stream.close()
      props
    }
    val serviceId = tmpProps.getProperty("serviceId")
    val apiKey = tmpProps.getProperty("apiKey")
    val fastlyCredentials = Some(FastlyCredentials(serviceId, apiKey))

    s3Credentials ++ fastlyCredentials
  }

  def keyRing(context:DeployContext): KeyRing = {
    KeyRing( SystemUser(keyFile = Configuration.sshKey.file),
                credentials(context.stage.name, context.project.applications))
  }

  def shutdown() {
    agent.foreach(_.shutdown())
    agent = None
  }
}