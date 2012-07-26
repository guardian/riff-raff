package deployment

import magenta.json.DeployInfoJsonReader
import magenta.Host
import akka.actor.ActorSystem
import akka.agent.Agent
import akka.util.duration._
import controllers.Logging

object DeployInfo extends Logging {
  private def getHostList = {
    try {
      import sys.process._
      DeployInfoJsonReader.parse("/opt/bin/deployinfo.json".!!)
    } catch {
      case e => log.error("Couldn't gather deployment information", e)
      throw e
    }
  }

  val system = ActorSystem("deploy")
  val agent = Agent[List[Host]](Nil)(system)

  def hostList: List[Host] = agent()

  def start() {
    try {
      system.scheduler.schedule(1 second, 1 minute) {
        log.info("Populating deployinfo hosts...")
        val hosts = getHostList
        agent update(hosts)
        log.info("Successfully retrieved deployinfo hosts (%d hosts found)" format hosts.size)
      }
    } catch {
      case e => log.error("Failed to setup deployinfo scheduler",e)
      throw e
    }
  }
  def shutdown() {
    system.shutdown()
  }
}
