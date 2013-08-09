package conf

import play.api.Play
import com.gu.management._
import logback.LogbackLevelPage
import com.gu.management.play.{ Management => PlayManagement }
import com.gu.conf.ConfigurationFactory
import java.io.File
import magenta._
import java.net.URL
import controllers.{DeployController, Logging}
import lifecycle.LifecycleWithoutApp
import java.util.UUID
import scala.Some
import collection.mutable
import persistence.{CollectionStats, Persistence}
import deployment.GuDomainsConfiguration
import akka.util.{Switch => AkkaSwitch}
import utils.{UnnaturalOrdering, ScheduledAgent}
import scala.concurrent.duration._

class Configuration(val application: String, val webappConfDirectory: String = "env") extends Logging {
  protected val configuration = ConfigurationFactory.getConfiguration(application, webappConfDirectory)

  implicit def option2getOrException[T](option: Option[T]) = new {
    def getOrException(exceptionMessage: String): T = {
      option.getOrElse {
        throw new IllegalStateException(exceptionMessage)
      }
    }
  }

  object auth {
    lazy val openIdUrl: String = configuration.getStringProperty("auth.openIdUrl").getOrException("No authentication URL configured")
    lazy val domains: List[String] = configuration.getStringPropertiesSplitByComma("auth.domains")
    object whitelist {
      lazy val useDatabase: Boolean = configuration.getStringProperty("auth.whitelist.useDatabase", "false") == "true"
      lazy val addresses: List[String] = configuration.getStringPropertiesSplitByComma("auth.whitelist.addresses")
    }
  }

  object concurrency {
    lazy val maxDeploys = configuration.getIntegerProperty("concurrency.maxDeploys", 8)
  }

  object continuousDeployment {
    lazy val enabled = configuration.getStringProperty("continuousDeployment.enabled", "false") == "true"
  }

  object credentials {
    def lookupSecret(service: String, id:String): Option[String] = {
      configuration.getStringProperty("credentials.%s.%s" format (service, id))
    }
  }

  object deployinfo {
    lazy val location: String = configuration.getStringProperty("deployinfo.location").getOrException("Deploy Info location not specified")
    lazy val mode: DeployInfoMode.Value = configuration.getStringProperty("deployinfo.mode").flatMap{ name =>
      DeployInfoMode.values.filter(_.toString.equalsIgnoreCase(name)).headOption
    }.getOrElse(DeployInfoMode.URL)
  }

  lazy val domains = GuDomainsConfiguration(configuration, prefix = "domains")

  object housekeeping {
    lazy val summariseDeploysAfterDays = configuration.getIntegerProperty("housekeeping.summariseDeploysAfterDays", 90)
    lazy val hour = configuration.getIntegerProperty("housekeeping.hour", 4)
    lazy val minute = configuration.getIntegerProperty("housekeeping.minute", 0)
  }

  object irc {
    lazy val isConfigured = name.isDefined && host.isDefined && channel.isDefined
    lazy val name = configuration.getStringProperty("irc.name")
    lazy val host = configuration.getStringProperty("irc.host")
    lazy val channel = configuration.getStringProperty("irc.channel")
  }

  object logging {
    lazy val verbose = configuration.getStringProperty("logging").map(_.equalsIgnoreCase("VERBOSE")).getOrElse(false)
  }

  object mongo {
    lazy val isConfigured = uri.isDefined
    lazy val uri = configuration.getStringProperty("mongo.uri")
    lazy val collectionPrefix = configuration.getStringProperty("mongo.collectionPrefix","")
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

  object sshKey {
    lazy val path: Option[String] = configuration.getStringProperty("sshKey.path")
    lazy val file: Option[File] = path map (new File(_))
  }

  object stages {
    lazy val order = configuration.getStringPropertiesSplitByComma("stages.order").filterNot(""==)
    lazy val ordering = UnnaturalOrdering(order, false)
  }

  object teamcity {
    lazy val serverURL = configuration.getStringProperty("teamcity.serverURL").map(new URL(_))
    lazy val useAuth = user.isDefined && password.isDefined
    lazy val user = configuration.getStringProperty("teamcity.user")
    lazy val password = configuration.getStringProperty("teamcity.password")
    lazy val pinSuccessfulDeploys = configuration.getStringProperty("teamcity.pinSuccessfulDeploys", "false") == "true"
    lazy val pinStages = configuration.getStringPropertiesSplitByComma("teamcity.pinStages").filterNot(""==)
    lazy val maximumPinsPerProject = configuration.getIntegerProperty("teamcity.maximumPinsPerProject", 5)
    lazy val pollingWindowMinutes = configuration.getIntegerProperty("teamcity.pollingWindowMinutes", 60)
    lazy val pollingPeriodSeconds = configuration.getIntegerProperty("teamcity.pollingPeriodSeconds", 60)
    lazy val fullUpdatePeriodSeconds = configuration.getIntegerProperty("teamcity.fullUpdatePeriodSeconds", 1800)
  }

  object urls {
    lazy val publicPrefix: String = configuration.getStringProperty("urls.publicPrefix", "http://localhost:9000")
  }

  override def toString(): String = configuration.toString
}

object Configuration extends Configuration("riff-raff", webappConfDirectory = "env")

object DeployInfoMode extends Enumeration {
  val URL = Value("URL")
  val Execute = Value("Execute")
}

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

object RequestMetrics extends com.gu.management.play.RequestMetrics.Standard

object DeployMetrics extends LifecycleWithoutApp {
  val runningDeploys = mutable.Buffer[UUID]()

  object DeployStart extends CountMetric("riffraff", "start_deploy", "Start deploy", "Number of deploys that are kicked off")
  object DeployComplete extends CountMetric("riffraff", "complete_deploy", "Complete deploy", "Number of deploys that completed", Some(DeployStart))
  object DeployFail extends CountMetric("riffraff", "fail_deploy", "Complete deploy", "Number of deploys that failed", Some(DeployStart))

  object DeployRunning extends GaugeMetric("riffraff", "running_deploys", "Running deploys", "Number of currently running deploys", () => runningDeploys.length)

  val all = Seq(DeployStart, DeployComplete, DeployFail, DeployRunning)

  val sink = new MessageSink {
    def message(message: MessageWrapper) {
      message.stack.top match {
        case StartContext(Deploy(parameters)) =>
          DeployStart.recordCount(1)
          runningDeploys += message.context.deployId
        case FailContext(Deploy(parameters)) =>
          DeployFail.recordCount(1)
          runningDeploys -= message.context.deployId
        case FinishContext(Deploy(parameters)) =>
          DeployComplete.recordCount(1)
          runningDeploys -= message.context.deployId
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
  object DatastoreRequest extends TimingMetric(
    "performance",
    "database_requests",
    "Database requests",
    "outgoing requests to the database"
  )
  val collectionStats = ScheduledAgent(5 seconds, 5 minutes, Map.empty[String, CollectionStats]) { map =>  Persistence.store.collectionStats }
  def dataSize: Long = collectionStats().values.map(_.dataSize).foldLeft(0L)(_ + _)
  def storageSize: Long = collectionStats().values.map(_.storageSize).foldLeft(0L)(_ + _)
  def deployCollectionCount: Long = collectionStats().get("%sdeployV2" format Configuration.mongo.collectionPrefix).map(_.documentCount).getOrElse(0L)
  object MongoDataSize extends GaugeMetric("mongo", "data_size", "MongoDB data size", "The size of the data held in mongo collections", () => dataSize)
  object MongoStorageSize extends GaugeMetric("mongo", "storage_size", "MongoDB storage size", "The size of the storage used by the MongoDB collections", () => storageSize)
  object MongoDeployCollectionCount extends GaugeMetric("mongo", "deploys_collection_count", "Deploys collection count", "The number of documents in the deploys collection", () => deployCollectionCount)
  val all = Seq(DatastoreRequest, MongoDataSize, MongoStorageSize, MongoDeployCollectionCount)
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
    RequestMetrics.asMetrics ++
    DeployMetrics.all ++
    DatastoreMetrics.all
}

case class AtomicSwitch(name: String, description: String, initiallyOn: Boolean = true) extends Switchable with Loggable {
  val delegate = new AkkaSwitch(initiallyOn)

  def isSwitchedOn = delegate.isOn

  def switchOn() = switchOn({})
  def switchOn( action: => Unit ): Boolean =
    delegate.switchOn {
      logger.info("Switching on " + name)
      action
    }

  def switchOff() = switchOff({})
  def switchOff( action: => Unit ): Boolean =
    delegate.switchOff {
      logger.info("Switching off " + name)
      action
    }

  def whileOn( action: => Unit ): Boolean = delegate.whileOn(action)
  def whileOff( action: => Unit ): Boolean = delegate.whileOff(action)
  def whileOnYield[T]( action: T ): Option[T] = delegate.whileOnYield( action )
  def whileOffYield[T]( action: T ): Option[T] = delegate.whileOffYield( action )
}

object Switches {
  //  val switch = new DefaultSwitch("name", "Description Text")
  val all: Seq[Switchable] = Healthcheck.switch :: DeployController.enableSwitches
}

