package deployment

import magenta.json.DeployInfoJsonReader
import magenta._
import akka.actor.ActorSystem
import akka.util.duration._
import controllers.Logging
import magenta.App
import conf.Configuration
import utils.ScheduledAgent
import java.io.File

object DeployInfoManager extends Logging {
  private def getDeployInfo = {
    try {
      import sys.process._
      log.info("Populating deployinfo hosts...")
      val deployInfo = if (new File(deployInfoLocation).exists)
        DeployInfoJsonReader.parse(deployInfoLocation.!!)
      else {
        log.warn("No file found at '%s', defaulting to empty DeployInfo" format (deployInfoLocation))
        DeployInfo(List(), Map())
      }

      log.info("Successfully retrieved deployinfo (%d hosts and %d data found)" format (
        deployInfo.hosts.size, deployInfo.data.values.map(_.size).fold(0)(_+_)))
      deployInfo
    } catch {
      case e => log.error("Couldn't gather deployment information", e)
      throw e
    }
  }
  // Should be configurable
  val deployInfoLocation = "/opt/bin/deployinfo.json"

  val system = ActorSystem("deploy")
  val agent = ScheduledAgent[DeployInfo](1 minute, 1 minute)(getDeployInfo)

  def deployInfo = agent()

  def hostList = deployInfo.hosts
  def dataList = deployInfo.data

  def credentials(stage:String,apps:Set[App]) : List[Credentials] = {
    apps.toList.flatMap(app => deployInfo.firstMatchingData("aws-keys",app,stage)).map(k => Configuration.s3.credentials(k.value)).distinct
  }

  def keyRing(context:DeployContext): KeyRing = {
    KeyRing( SystemUser(keyFile = Configuration.sshKey.file),
                credentials(context.stage.name, context.project.applications))
  }

  def shutdown() {
    agent.shutdown()
  }
}