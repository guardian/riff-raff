package notification

import magenta._
import java.util.{Date, UUID}
import magenta.MessageStack
import magenta.FailContext
import magenta.Deploy
import magenta.FinishContext
import magenta.StartContext
import com.rabbitmq.client.{Channel, ConnectionFactory}
import akka.actor._
import controllers.{routes, Logging}
import net.liftweb.json._
import net.liftweb.json.Serialization.write
import conf.Configuration
import conf.Configuration.mq.QueueDetails
import lifecycle.LifecycleWithoutApp
import akka.actor.SupervisorStrategy.Restart
import scala.Some
import akka.actor.OneForOneStrategy
import magenta.DeployParameters
import akka.util.duration._

/*
 Send deploy events to graphite
 */

object MessageQueue extends LifecycleWithoutApp with Logging {
  trait Event
  case class Notify(event: AlertaEvent) extends Event
  case class Init(queueDetailList: List[QueueDetails]) extends Event
  case class Shutdown() extends Event

  private lazy val system = ActorSystem("notify")
  val actor = try {
      Some(system.actorOf(Props[MessageQueueController], "mq-controller"))
    } catch {
      case t:Throwable =>
        log.error("Couldn't start MQ controller", t)
        None
    }

  def sendMessage(event: Event) {
    actor.foreach(_ ! event)
  }

  lazy val sink = new MessageSink {
    def message(uuid: UUID, stack: MessageStack) {
      stack.top match {
        case StartContext(Deploy(parameters)) =>
          sendMessage(Notify(AlertaEvent(DeployEvent.Start, uuid, parameters)))
        case FailContext(Deploy(parameters), exception) =>
          sendMessage(Notify(AlertaEvent(DeployEvent.Fail, uuid, parameters)))
        case FinishContext(Deploy(parameters)) =>
          sendMessage(Notify(AlertaEvent(DeployEvent.Complete, uuid, parameters)))
        case _ =>
      }
    }
  }

  def init() {
    val targets = Configuration.mq.queueTargets
    if (targets.isEmpty)
      log.info("No message queue targets to initialise")
    else {
      sendMessage(Init(targets))
      MessageBroker.subscribe(sink)
    }
  }

  def shutdown() {
    sendMessage(Shutdown())
    MessageBroker.unsubscribe(sink)
  }
}

class MessageQueueController extends Actor with Logging {
  import MessageQueue._
  var mqClients = Map.empty[QueueDetails, ActorRef]

  override def supervisorStrategy() = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute ) {
    case _ => Restart
  }

  def receive = {
    case Init(queueDetailList) => {
      mqClients ++= queueDetailList.flatMap { queueTarget =>
        try {
          val actor = context.actorOf(Props(new MessageQueueClient(queueTarget)), "mq-client-%s" format queueTarget.name.replace("/","-"))
          context.watch(actor)
          Some(queueTarget -> actor)
        } catch {
          case t: Throwable => None
        }
      }
      log.info("Message queue targets initialised")
    }
    case Shutdown() => {
      mqClients.values.foreach(context.stop)
      mqClients = Map.empty
    }
    case Notify(event) => {
      try {
        mqClients.values.foreach(_ ! Notify(event))
      } catch {
        case e:Throwable => log.error("Exception whilst dispatching event", e)
      }
    }
    case Terminated(actor) => {
      log.warn("Received terminate from %s " format actor.path)
      mqClients.find(_._2 == actor).map { case(key,value) =>
        mqClients -= key
      }
    }
  }

  override def postStop() {
    log.info("I've been stopped")
  }
}

class MessageQueueClient(queueDetails:QueueDetails) extends Actor with Logging {
  import MessageQueue._

  var channel: Option[Channel] = None

  override def preStart() {
    log.info("Initialising %s" format queueDetails)
    channel = try {
      val factory = new ConnectionFactory()
      factory.setHost(queueDetails.hostname)
      factory.setPort(queueDetails.port)
      val conn = factory.newConnection()
      Some(conn.createChannel())
    } catch {
      case e =>
        log.error("Error initialising %s" format queueDetails,e)
        throw e
    }
    channel.foreach(_.queueDeclare(queueDetails.queueName, true, false, false, null))
    log.info("Initialisation complete to %s" format queueDetails)
  }

  def sendToMQ(event:AlertaEvent) {
    log.info("Sending following message to %s: %s" format (queueDetails, event))
    channel.foreach(_.basicPublish("",queueDetails.queueName, null, event.toJson.getBytes))
  }

  def receive = {
    case Notify(event) => {
      try {
        sendToMQ(event)
      } catch {
        case t:Throwable => log.error("Error sending message to %s: %s" format(queueDetails, event))
        throw t
      }
    }
  }

  override def postStop() {
    channel.foreach{ realChannel =>
      val conn = realChannel.getConnection
      realChannel.close()
      conn.close()
    }
  }
}

object DeployEvent extends Enumeration {
  val Start = Value("DeployStarted")
  val Complete = Value("DeployCompleted")
  val Fail = Value("DeployFailed")
}

object AlertaEvent {
  def apply(event:DeployEvent.Value, uuid:UUID, params:DeployParameters): AlertaEvent = {
    val environment = params.stage.name
    val project = params.build.projectName
    val build = params.build.id
    val user = params.deployer.name

    case class Severity(name: String, code: Int)
    val inform = Severity("INFORM", 6)
    val minor = Severity("MINOR", 3)
    val severityMap = Map( DeployEvent.Complete -> inform, DeployEvent.Fail -> minor, DeployEvent.Start -> inform)

    new AlertaEvent(
      "riffraff/%s" format java.net.InetAddress.getLocalHost.getHostName,
      "Deploys",
      severityMap(event).name,
      List(project.split(":").head),
      List("release:%s" format build, "user:%s" format user),
      "Deploy of %s started" format project,
      new Date(),
      "Release %s" format build,
      event.toString,
      List(environment),
      severityMap(event).code,
      86400,
      project,
      DeployEvent.values.map(_.toString).toList,
      "%s - INFORM %s of %s build %s" format (environment, event, project, build),
      "n/a",
      "deployAlert",
      UUID.randomUUID().toString,
      "%s%s" format (Configuration.urls.publicPrefix, routes.Deployment.viewUUID(uuid.toString).url)
    )
  }
}

case class AlertaEvent(
  origin: String,
  group: String,
  severity: String,
  service: List[String],
  tags: List[String],
  text: String,
  createTime: Date,
  value: String,
  event: String,
  environment: List[String],
  severityCode: Int,
  timeout: Int,
  resource: String,
  correlatedEvents: List[String],
  summary: String,
  thresholdInfo: String,
  `type`: String,
  id: String,
  moreInfo: String = ""
) {
  def toJson: String = {
    implicit val formats = Serialization.formats(NoTypeHints)
    write(this)
  }
}

