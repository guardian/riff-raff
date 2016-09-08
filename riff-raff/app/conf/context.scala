package conf

import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.s3.AmazonS3Client
import com.gu.management._
import logback.LogbackLevelPage
import com.gu.management.play.{Management => PlayManagement}
import com.gu.conf.ConfigurationFactory
import magenta._
import controllers.{Logging, routes}
import lifecycle.{LifecycleWithoutApp, ShutdownWhenInactive}
import java.util.UUID

import com.amazonaws.ClientConfiguration
import com.amazonaws.regions.{Region, Regions}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.{DescribeTagsRequest, Filter}
import com.amazonaws.util.EC2MetadataUtils

import collection.mutable
import persistence.{CollectionStats, Persistence}
import deployment.{DeployMetricsActor, Deployments}
import utils.{ScheduledAgent, UnnaturalOrdering}

import scala.concurrent.duration._
import scala.collection.JavaConverters._
import org.joda.time.format.ISODateTimeFormat
import com.gu.googleauth.GoogleAuthConfig
import riffraff.BuildInfo

import scala.util.{Success, Try}

class Configuration(val application: String, val webappConfDirectory: String = "env") extends Logging {
  protected val configuration = ConfigurationFactory.getConfiguration(application, webappConfDirectory)

  implicit class RichOption[T](val option: Option[T]) {
    def getOrException(exceptionMessage: String): T = {
      option.getOrElse {
        throw new IllegalStateException(exceptionMessage)
      }
    }
  }

  lazy val stage: String = {
    val theStage = Try(EC2MetadataUtils.getInstanceId) match {
      case Success(instanceId) if instanceId != null =>
        val request = new DescribeTagsRequest().withFilters(
          new Filter("resource-type").withValues("instance"),
          new Filter("resource-id").withValues(instanceId)
        )
        val ec2Client = Regions.getCurrentRegion.createClient(classOf[AmazonEC2Client], credentialsProviderChain(None, None), null)
        try {
          val describeTagsResult = ec2Client.describeTags(request)
          describeTagsResult.getTags.asScala
            .collectFirst{ case t if t.getKey == "Stage" => t.getValue }
            .getOrException("Couldn't find a Stage tag on the Riff Raff instance")
        } finally {
          ec2Client.shutdown()
        }
      case _ => "DEV" // if we couldn't get an instance ID, we must be on a developer's machine
    }
    log.info(s"Riff Raff's stage = $theStage")
    theStage
  }

  object auth {
    lazy val domains: List[String] = configuration.getStringPropertiesSplitByComma("auth.domains")
    object whitelist {
      lazy val useDatabase: Boolean = configuration.getStringProperty("auth.whitelist.useDatabase", "false") == "true"
      lazy val addresses: List[String] = configuration.getStringPropertiesSplitByComma("auth.whitelist.addresses")
    }
    lazy val clientId: String = configuration.getStringProperty("auth.clientId").getOrException("No client ID configured")
    lazy val clientSecret: String = configuration.getStringProperty("auth.clientSecret").getOrException("No client secret configured")
    lazy val redirectUrl: String = configuration.getStringProperty("auth.redirectUrl").getOrElse(s"${urls.publicPrefix}${routes.Login.oauth2Callback().url}")
    lazy val domain: Option[String] = configuration.getStringProperty("auth.domain")
    lazy val googleAuthConfig = GoogleAuthConfig(auth.clientId, auth.clientSecret, auth.redirectUrl, auth.domain)
  }

  object concurrency {
    lazy val maxDeploys = configuration.getIntegerProperty("concurrency.maxDeploys", 8)
  }

  object continuousDeployment {
    lazy val enabled = configuration.getStringProperty("continuousDeployment.enabled", "false") == "true"
    val dynamoClient = Region.getRegion(Regions.EU_WEST_1).createClient(
      classOf[AmazonDynamoDBAsyncClient],
      credentialsProviderChain(None, None),
      new ClientConfiguration()
    )
  }

  object credentials {
    def lookupSecret(service: String, id:String): Option[String] = {
      configuration.getStringProperty("credentials.%s.%s" format (service, id))
    }
  }

  object freeze {
    private val formatter = ISODateTimeFormat.dateTime()
    lazy val startDate = configuration.getStringProperty("freeze.startDate").map(formatter.parseDateTime)
    lazy val endDate = configuration.getStringProperty("freeze.endDate").map(formatter.parseDateTime)
    lazy val message = configuration.getStringProperty("freeze.message", "There is currently a change freeze. I'm not going to stop you, but you should think carefully about what you are about to do.")
    lazy val stages = configuration.getStringPropertiesSplitByComma("freeze.stages")
  }

  object housekeeping {
    lazy val summariseDeploysAfterDays = configuration.getIntegerProperty("housekeeping.summariseDeploysAfterDays", 90)
    lazy val hour = configuration.getIntegerProperty("housekeeping.hour", 4)
    lazy val minute = configuration.getIntegerProperty("housekeeping.minute", 0)
  }

  object logging {
    lazy val verbose = configuration.getStringProperty("logging").exists(_.equalsIgnoreCase("VERBOSE"))
  }

  object lookup {
    lazy val source = configuration.getStringProperty("lookup.source", "deployinfo")
    lazy val staleMinutes: Int = configuration.getIntegerProperty("lookup.staleMinutes", 15)
    lazy val prismUrl = configuration.getStringProperty("lookup.prismUrl").getOrException("Prism URL not specified")
    lazy val timeoutSeconds = configuration.getIntegerProperty("lookup.timeoutSeconds", 30)
  }

  object mongo {
    lazy val isConfigured = uri.isDefined
    lazy val uri = configuration.getStringProperty("mongo.uri")
    lazy val collectionPrefix = configuration.getStringProperty("mongo.collectionPrefix","")
  }

  object stages {
    lazy val order = configuration.getStringPropertiesSplitByComma("stages.order").filterNot(""==)
    lazy val ordering = UnnaturalOrdering(order, aliensAtEnd = false)
  }

  object artifact {
    object aws {
      implicit lazy val bucketName = configuration.getStringProperty("artifact.aws.bucketName").getOrException("Artifact bucket name not configured")
      lazy val accessKey = configuration.getStringProperty("artifact.aws.accessKey")
      lazy val secretKey = configuration.getStringProperty("artifact.aws.secretKey")
      implicit lazy val client = new AmazonS3Client(credentialsProvider)
      lazy val credentialsProvider = credentialsProviderChain(accessKey, secretKey)
    }
  }

  object build {
    lazy val pollingPeriodSeconds = configuration.getIntegerProperty("build.pollingPeriodSeconds", 10)
    object aws {
      implicit lazy val bucketName = configuration.getStringProperty("build.aws.bucketName")
      lazy val accessKey = configuration.getStringProperty("build.aws.accessKey")
      lazy val secretKey = configuration.getStringProperty("build.aws.secretKey")
      implicit lazy val client = new AmazonS3Client(credentialsProvider)
      lazy val credentialsProvider = credentialsProviderChain(accessKey, secretKey)
    }
  }

  object tag {
    object aws {
      implicit lazy val bucketName = configuration.getStringProperty("tag.aws.bucketName")
      lazy val accessKey = configuration.getStringProperty("tag.aws.accessKey")
      lazy val secretKey = configuration.getStringProperty("tag.aws.secretKey")
      implicit lazy val client = new AmazonS3Client(credentialsProvider)
      lazy val credentialsProvider = credentialsProviderChain(accessKey, secretKey)
    }
  }

  def credentialsProviderChain(accessKey: Option[String], secretKey: Option[String]): AWSCredentialsProviderChain =
    new AWSCredentialsProviderChain(
      new AWSCredentialsProvider {
        override def getCredentials: AWSCredentials = (for {
          key <- accessKey
          secret <- secretKey
        } yield new BasicAWSCredentials(key, secret)).orNull

        override def refresh(): Unit = {}
      },
      new EnvironmentVariableCredentialsProvider,
      new SystemPropertiesCredentialsProvider,
      new ProfileCredentialsProvider("deployTools"),
      new InstanceProfileCredentialsProvider
    )

  object urls {
    lazy val publicPrefix: String = configuration.getStringProperty("urls.publicPrefix", "http://localhost:9000")
  }

  val version:String = BuildInfo.buildNumber

  override def toString: String = configuration.toString
}

object Configuration extends Configuration("riff-raff", webappConfDirectory = "env")

object DeployInfoMode extends Enumeration {
  val URL = Value("URL")
  val Execute = Value("Execute")
}

object Management extends PlayManagement {
  val applicationName = "riff-raff"

  val pages = List(
    new BuildInfoPage,
    new HealthcheckManagementPage,
    new Switchboard(applicationName, Switches.all),
    StatusPage(applicationName, Metrics.all),
    new LogbackLevelPage(applicationName)
  )
}

class BuildInfoPage extends ManagementPage {
  val path = "/management/manifest"
  def get(req: HttpRequest) = response
  lazy val response = PlainTextResponse(BuildInfo.toString)
}

object PlayRequestMetrics extends com.gu.management.play.RequestMetrics.Standard

object DeployMetrics extends LifecycleWithoutApp {
  val runningDeploys = mutable.Buffer[UUID]()

  object DeployStart extends CountMetric("riffraff", "start_deploy", "Start deploy", "Number of deploys that are kicked off")
  object DeployComplete extends CountMetric("riffraff", "complete_deploy", "Complete deploy", "Number of deploys that completed", Some(DeployStart))
  object DeployFail extends CountMetric("riffraff", "fail_deploy", "Complete deploy", "Number of deploys that failed", Some(DeployStart))

  object DeployRunning extends GaugeMetric("riffraff", "running_deploys", "Running deploys", "Number of currently running deploys", () => runningDeploys.length)

  val all = Seq(DeployStart, DeployComplete, DeployFail, DeployRunning)

  val messageSub = DeployReporter.messages.subscribe(message => {
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
  })

  def init() { }
  def shutdown() { messageSub.unsubscribe() }
}

object TaskMetrics {
  object TaskTimer extends TimingMetric("riffraff", "task_run", "Tasks running", "Timing of deployment tasks")
  object TaskStartLatency extends TimingMetric("riffraff", "task_start_latency", "Task start latency", "Timing of deployment task start latency", Some(TaskTimer))
  object TasksRunning extends GaugeMetric("riffraff", "running_tasks", "Running tasks", "Number of currently running tasks", () => DeployMetricsActor.runningTaskCount)
  val all = Seq(TaskTimer, TaskStartLatency, TasksRunning)
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
    //PlayRequestMetrics.asMetrics ++
    DeployMetrics.all ++
    DatastoreMetrics.all ++
    TaskMetrics.all
}

object Switches {
  //  val switch = new DefaultSwitch("name", "Description Text")
  val all: Seq[Switchable] = ShutdownWhenInactive.switch :: Healthcheck.switch :: Deployments.enableSwitches
}

