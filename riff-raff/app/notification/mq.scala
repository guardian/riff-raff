package notification

import magenta._
import java.util.{Date, UUID}
import magenta.MessageStack
import magenta.FailContext
import magenta.Deploy
import magenta.FinishContext
import magenta.StartContext
import com.rabbitmq.client.{ConnectionFactory, Connection}
import akka.actor.{Props, ActorSystem, Actor}
import controllers.{routes, Logging}
import net.liftweb.json._
import net.liftweb.json.Serialization.write
import conf.Configuration
import conf.Configuration.mq.QueueDetails
import lifecycle.LifecycleWithoutApp

/*
 Send deploy events to graphite
 */

object MessageQueue extends LifecycleWithoutApp {
  trait Event
  case class Notify(event: AlertaEvent) extends Event

  lazy val system = ActorSystem("notify")
  val actor =
    Configuration.mq.queueTargets.flatMap{ queueTarget =>
      try { Some(system.actorOf(Props(new MessageQueueClient(queueTarget)), "mq-client")) } catch { case t:Throwable => None }
    }

  def sendMessage(event: AlertaEvent) {
    actor foreach (_ ! Notify(event))
  }

  val sink = new MessageSink {
    def message(uuid: UUID, stack: MessageStack) {
      stack.top match {
        case StartContext(Deploy(parameters)) =>
          sendMessage(AlertaEvent(DeployEvent.Start, uuid, parameters))
        case FailContext(Deploy(parameters), exception) =>
          sendMessage(AlertaEvent(DeployEvent.Fail, uuid, parameters))
        case FinishContext(Deploy(parameters)) =>
          sendMessage(AlertaEvent(DeployEvent.Complete, uuid, parameters))
        case _ =>
      }
    }
  }

  def init() {
    MessageBroker.subscribe(sink)
  }

  def shutdown() {
    MessageBroker.unsubscribe(sink)
    actor foreach(system.stop)
  }
}




class MessageQueueClient(queueDetails:QueueDetails) extends Actor with Logging {
  import MessageQueue._

  val channel = try {
    val factory = new ConnectionFactory()
    factory.setHost(queueDetails.hostname)
    factory.setPort(queueDetails.port)
    val conn = factory.newConnection()
    conn.createChannel()
  } catch {
    case e =>
      log.error("Error initialising %s" format queueDetails,e)
      throw e
  }

  channel.queueDeclare(queueDetails.queueName, true, false, false, null)

  log.info("Initialisation complete to %s" format queueDetails)

  def sendToMQ(event:AlertaEvent) {
    log.info("Sending following message to %s: %s" format (queueDetails, event))
    channel.basicPublish("",queueDetails.queueName, null, event.toJson.getBytes)
  }

  def receive = {
    case Notify(event) => {
      sendToMQ(event)
    }
  }

  override def postStop() {
    val conn = channel.getConnection
    channel.close()
    conn.close()
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
      "%s%s" format (Configuration.urls.publicPrefix, routes.Deployment.deployLog(uuid.toString).url)
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

