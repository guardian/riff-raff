package notification

import akka.actor.{Actor, ActorSystem, Props}
import com.gu.management.ManagementBuildInfo
import conf.Configuration
import controllers.Logging
import lifecycle.LifecycleWithoutApp
import magenta._
import org.pircbotx.PircBotX

object IrcClient extends LifecycleWithoutApp {
  trait Event
  case class Notify(message: String) extends Event

  lazy val system = ActorSystem("notify")
  val actor = if (Configuration.irc.isConfigured) Some(system.actorOf(Props[IrcClient], "irc-client")) else None

  def sendMessage(message: String) {
    actor.foreach(_ ! Notify(message))
  }

  val messageSub = MessageBroker.messages.subscribe(message => {
    message.stack.top match {
      case StartContext(Deploy(parameters)) =>
        sendMessage("[%s] Starting deploy of %s build %s (using recipe %s) to %s" format
          (parameters.deployer.name, parameters.build.projectName, parameters.build.id, parameters.recipe.name, parameters.stage.name))
      case FailContext(Deploy(parameters)) =>
        sendMessage("[%s] FAILED: deploy of %s build %s (using recipe %s) to %s" format
          (parameters.deployer.name, parameters.build.projectName, parameters.build.id, parameters.recipe.name, parameters.stage.name))
      case FinishContext(Deploy(parameters)) =>
        sendMessage("[%s] Finished deploy of %s build %s (using recipe %s) to %s" format
          (parameters.deployer.name, parameters.build.projectName, parameters.build.id, parameters.recipe.name, parameters.stage.name))
      case _ =>
    }
  })

  def init() {}
  def shutdown() {
    messageSub.unsubscribe()
    actor.foreach(system.stop)
  }
}

class IrcClient extends Actor with Logging {
  import notification.IrcClient._

  val name = Configuration.irc.name.get
  val host = Configuration.irc.host.get
  val channel = Configuration.irc.channel.get
  log.info("Starting IRC: Joining %s on %s as %s" format (channel,host,name))

  val ircBot = new PircBotX()

  try {
    ircBot.setName(name)
    ircBot.connect(host)
    ircBot.joinChannel(channel)
  } catch {
    case e:Throwable =>
      log.error(e.toString)
  }

  log.info("Initialisation complete")
  log.info(ircBot.toString)
  sendToChannel("riff-raff (build %s) started" format ManagementBuildInfo.version)

  def sendToChannel(message:String) {
    log.info("Sending: %s" format message)
    ircBot.sendMessage(Configuration.irc.channel.get, message)
  }

  def receive = {
    case Notify(message) => {
      sendToChannel(message)
    }
  }

  override def postStop() {
    ircBot.quitServer("riff-raff (build %s) shutting down" format ManagementBuildInfo.version)
  }
}

