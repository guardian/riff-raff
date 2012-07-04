package notification

import controllers.Logging
import conf.Configuration
import org.pircbotx.PircBotX
import scala.collection.JavaConversions._
import akka.actor._

object IrcClient {


  trait Event
  case class Notify(message: String) extends Event

  lazy val system = ActorSystem("notify")
  val actor = if (Configuration.irc.isConfigured) Some(system.actorOf(Props[IrcClient], "irc-client")) else None

  def notify(message: String) {
    actor.foreach(_ ! Notify(message))
  }

  def init(): Option[ActorRef] = {
    actor
  }

  def shutdown() { actor.foreach(system.stop) }

}

class IrcClient extends Actor with Logging {
  import IrcClient._

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
    case e =>
      log.error(e.toString)
  }

  log.info("Initialisation complete")
  log.info(ircBot.toString)

  def sendToChannel(message:String) { ircBot.sendMessage(Configuration.irc.channel.get, message) }

  def receive = {
    case Notify(message) => {
      sendToChannel(message)
    }
  }

  override def postStop() {
    ircBot.disconnect()
  }
}

