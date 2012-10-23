package conf

import play.api.Play
import com.gu.management._
import logback.LogbackLevelPage
import com.gu.management.play.{ Management => PlayManagement }
import com.gu.conf.ConfigurationFactory
import java.io.File
import magenta._
import java.net.URL
import controllers.Logging
import lifecycle.LifecycleWithoutApp
import java.util.UUID
import magenta.S3Credentials
import magenta.MessageStack
import magenta.Deploy
import scala.Some
import magenta.FailContext
import magenta.StartContext
import collection.mutable
import datastore.DataStore

class Configuration(val application: String, val webappConfDirectory: String = "env") extends Logging {
  protected val configuration = ConfigurationFactory.getConfiguration(application, webappConfDirectory)

  implicit def option2getOrException[T](option: Option[T]) = new {
    def getOrException(exceptionMessage: String): T = {
      option.getOrElse {
        throw new IllegalStateException(exceptionMessage)
      }
    }
  }

  object urls {
    lazy val publicPrefix: String = configuration.getStringProperty("urls.publicPrefix", "http://localhost:9000")
  }

  object sshKey {
    lazy val path: Option[String] = configuration.getStringProperty("sshKey.path")
    lazy val file: Option[File] = path map (new File(_))
  }

  object logging {
    lazy val verbose = configuration.getStringProperty("logging").map(_.equalsIgnoreCase("VERBOSE")).getOrElse(false)
  }

  object s3 {
    def credentials(accessKey: String) = {
      val secretKey = configuration.getStringProperty("s3.secretAccessKey.%s" format accessKey).getOrException("No S3 secret access key configured for %s" format accessKey)
      S3Credentials(accessKey,secretKey)
    }
  }

  object mongo {
    lazy val isConfigured = uri.isDefined
    lazy val uri = configuration.getStringProperty("mongo.uri")
    lazy val collectionPrefix = configuration.getStringProperty("mongo.collectionPrefix","")
  }

  object irc {
    lazy val isConfigured = name.isDefined && host.isDefined && channel.isDefined
    lazy val name = configuration.getStringProperty("irc.name")
    lazy val host = configuration.getStringProperty("irc.host")
    lazy val channel = configuration.getStringProperty("irc.channel")
  }

  object mq {
    lazy val isConfigured = !queueTargets.isEmpty

    case class QueueDetails(name: String, hostname:String, port:Int, queueName:String)
    object QueueDetails {
      private lazy val QueueTarget = """^(.+):(.+)/(.+)$""".r
      def apply(server:String): Option[QueueDetails] = { server match {
        case QueueTarget(hostname, port, queueName) => Some(QueueDetails(server, hostname, port.toInt, queueName))
        case _ =>
          log.warn("Couldn't parse queue target: %s" format server)
          None
      } }
    }

    lazy val queueTargets: List[QueueDetails] = configuration.getStringPropertiesSplitByComma("mq.queueTargets").flatMap(QueueDetails(_))
  }

  object teamcity {
    lazy val serverURL = new URL(configuration.getStringProperty("teamcity.serverURL").getOrException("Teamcity server URL not configured"))
  }

  object continuousDeployment {
    private lazy val ProjectToStageRe = """^(.+)->(.+)$""".r
    lazy val configLine = configuration.getStringProperty("continuous.deployment", "")
    lazy val buildToStageMap = configLine.split("\\s").flatMap{ entry =>
        entry match {
          case ProjectToStageRe(project, stageList) =>  Some(project -> stageList.split(",").toList)
          case _ => None
        }
    }.toMap
    lazy val enabled = configuration.getStringProperty("continuous.deployment.enabled", "false") == "true"
  }

  override def toString(): String = configuration.toString
}

object Configuration extends Configuration("riff-raff", webappConfDirectory = "env")

object Management extends PlayManagement {
  val applicationName = Play.current.configuration.getString("application.name").get

  val pages = List(
    new ManifestPage,
    new HealthcheckManagementPage,
    new Switchboard(applicationName, Switches.all),
    StatusPage(applicationName, Metrics.all),
    new LogbackLevelPage(applicationName)
  )
}

object RequestMetrics {
  object RequestTimingMetric extends TimingMetric(
    "performance",
    "requests",
    "Client requests",
    "incoming requests to the application"
  )

  object DatastoreRequest extends TimingMetric(
    "performance",
    "database_requests",
    "Database requests",
    "outgoing requests to the database",
    Some(RequestTimingMetric)
  )

  object Request200s extends CountMetric("request-status", "200_ok", "200 Ok", "number of pages that responded 200")
  object Request50xs extends CountMetric("request-status", "50x_error", "50x Error", "number of pages that responded 50x")
  object Request404s extends CountMetric("request-status", "404_not_found", "404 Not found", "number of pages that responded 404")
  object Request30xs extends CountMetric("request-status", "30x_redirect", "30x Redirect", "number of pages that responded with a redirect")
  object RequestOther extends CountMetric("request-status", "other", "Other", "number of pages that responded with an unexpected status code")

  val all = Seq(RequestTimingMetric, DatastoreRequest, Request200s, Request50xs, Request404s, RequestOther, Request30xs)
}

object DeployMetrics extends LifecycleWithoutApp {
  val runningDeploys = mutable.Buffer[UUID]()

  object DeployStart extends CountMetric("riffraff", "start_deploy", "Start deploy", "Number of deploys that are kicked off")
  object DeployComplete extends CountMetric("riffraff", "complete_deploy", "Complete deploy", "Number of deploys that completed", Some(DeployStart))
  object DeployFail extends CountMetric("riffraff", "fail_deploy", "Complete deploy", "Number of deploys that failed", Some(DeployStart))

  object DeployRunning extends GaugeMetric("riffraff", "running_deploys", "Running deploys", "Number of currently running deploys", () => runningDeploys.length)

  val all = Seq(DeployStart, DeployComplete, DeployFail, DeployRunning)

  val sink = new MessageSink {
    def message(uuid: UUID, stack: MessageStack) {
      stack.top match {
        case StartContext(Deploy(parameters)) =>
          DeployStart.recordCount(1)
          runningDeploys += uuid
        case FailContext(Deploy(parameters), exception) =>
          DeployFail.recordCount(1)
          runningDeploys -= uuid
        case FinishContext(Deploy(parameters)) =>
          DeployComplete.recordCount(1)
          runningDeploys -= uuid
        case _ =>
      }
    }
  }

  def init() { MessageBroker.subscribe(sink) }
  def shutdown() { MessageBroker.unsubscribe(sink) }
}

object MessageMetrics {
  object IRCMessages extends TimingMetric(
    "messages", "irc_messages", "IRC messages", "messages sent to the IRC channel"
  )
  object MQMessages extends TimingMetric(
    "messages", "mq_messages", "MQ messages", "messages sent to the message queue"
  )

  val all = Seq(IRCMessages,MQMessages)
}

object DatastoreMetrics {
  object MongoDataSize extends GaugeMetric("mongo", "mongo_data_size", "MongoDB data size", "The size of the data held in mongo collections", () => DataStore.dataSize)
  object MongoStorageSize extends GaugeMetric("mongo", "mongo_storage_size", "MongoDB storage size", "The size of the storage used by the MongoDB collections", () => DataStore.storageSize)
}

object LoginCounter extends CountMetric("webapp",
  "login_attempts",
  "Login attempts",
  "Number of attempted logins")

object FailedLoginCounter extends CountMetric("webapp",
  "failed_logins",
  "Failed logins",
  "Number of failed logins")

object Metrics {
  val all: Seq[Metric] =
    magenta.metrics.MagentaMetrics.all ++
    Seq(LoginCounter, FailedLoginCounter) ++
    RequestMetrics.all ++
    DeployMetrics.all
}

object Switches {
  //  val switch = new DefaultSwitch("name", "Description Text")
  val all: Seq[Switchable] = List(Healthcheck.switch)
}

