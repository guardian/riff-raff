package deployment

import magenta.json.DeployInfoJsonReader
import magenta._
import akka.actor.ActorSystem
import akka.util.duration._
import controllers.Logging
import magenta.App
import conf.Configuration
import utils.ScheduledAgent

object DeployInfoManager extends Logging {
  private def getDeployInfo = {
    try {
      import sys.process._
      log.info("Populating deployinfo hosts...")
      val deployInfo = DeployInfoJsonReader.parse("/opt/bin/deployinfo.json".!!)
      log.info("Successfully retrieved deployinfo (%d hosts and %d keys found)" format(deployInfo.hosts.size, deployInfo.keys.size))
      deployInfo
    } catch {
      case e => log.error("Couldn't gather deployment information", e)
      throw e
    }
  }

  val system = ActorSystem("deploy")
  val agent = ScheduledAgent[DeployInfo](1 minute, 1 minute)(getDeployInfo)

  def deployInfo = agent()

  def hostList = deployInfo.hosts
  def keyList = deployInfo.keys

  def credentials(stage:String,apps:Set[App]) : List[Credentials] = {
    apps.toList.flatMap(app => deployInfo.firstMatchingKey(app,stage)).map(k => Configuration.s3.credentials(k.key))
  }

  def keyRing(context:DeployContext): KeyRing = {
    KeyRing( SystemUser(keyFile = Some(Configuration.sshKey.file)),
                credentials(context.stage.name, context.project.applications))
  }

  def shutdown() {
    agent.shutdown()
  }
}