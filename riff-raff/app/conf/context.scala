package conf

import java.io.File
import java.util.UUID

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.{Region, RegionUtils, Regions}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder
import com.amazonaws.services.ec2.model.{DescribeTagsRequest, Filter}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.amazonaws.services.sns.AmazonSNSAsyncClientBuilder
import com.amazonaws.util.EC2MetadataUtils
import com.gu.management._
import com.gu.management.logback.LogbackLevelPage
import com.typesafe.config.ConfigFactory
import controllers.{Logging, routes}
import deployment.Deployments
import deployment.actors.DeployMetricsActor
import lifecycle.{Lifecycle, ShutdownWhenInactive}
import magenta._
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, Days}
import persistence.{CollectionStats, Persistence}
import riffraff.BuildInfo
import utils.{ScheduledAgent, UnnaturalOrdering}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Success, Try}

object Config extends Logging {

  private val applicationConf = ConfigFactory.parseResources("application.conf")
  private val userConf = ConfigFactory.parseFile(new File(s"${scala.util.Properties.userHome}/.gu/riff-raff.conf"))
  private val configuration = userConf.withFallback(applicationConf).resolve()

  private def getString(path: String): String = configuration.getString(path)
  private def getStringOpt(path: String): Option[String] = Try(configuration.getString(path)).toOption
  private def getStringList(path: String): List[String] = getStringOpt(path).map(_.split(",").map(_.trim).toList).getOrElse(List.empty)
  private def getBooleanOpt(path: String): Option[Boolean] = Try(configuration.getBoolean(path)).toOption
  private def getIntOpt(path: String): Option[Int] = Try(configuration.getInt(path)).toOption

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
        val ec2Client = AmazonEC2ClientBuilder.standard()
          .withCredentials(credentialsProviderChain(None, None))
          .withRegion(Regions.getCurrentRegion.getName)
          .build()
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
    lazy val domains: List[String] = getStringList("auth.domains")
    object whitelist {
      lazy val useDatabase: Boolean = getBooleanOpt("auth.whitelist.useDatabase").getOrElse(false)
      lazy val addresses: List[String] = getStringList("auth.whitelist.addresses")
    }

    lazy val clientId: String = getStringOpt("auth.clientId").getOrException("No client ID configured")
    lazy val clientSecret: String = getStringOpt("auth.clientSecret").getOrException("No client secret configured")
    lazy val redirectUrl: String = getStringOpt("auth.redirectUrl").getOrElse(s"${urls.publicPrefix}${routes.Login.oauth2Callback().url}")
    lazy val domain: String = getStringOpt("auth.domain").getOrException("No auth domain configured")
    lazy val superusers: List[String] = getStringList("auth.superusers")
    lazy val secretStateSupplierKeyName: String = getStringOpt("auth.secretStateSupplier.keyName").getOrElse("/RiffRaff/PlayApplicationSecret")
    lazy val secretStateSupplierRegion: String = getStringOpt("auth.secretStateSupplier.region").getOrElse("eu-west-1")
  }

  object concurrency {
    lazy val maxDeploys = getIntOpt("concurrency.maxDeploys").getOrElse(8)
  }

  object continuousDeployment {
    lazy val enabled = getBooleanOpt("continuousDeployment.enabled").getOrElse(false)
  }

  object scheduledDeployment {
    lazy val enabled = getBooleanOpt("scheduledDeployment.enabled").getOrElse(false)
    lazy val regionName = getStringOpt("scheduledDeployment.aws.region").getOrElse("eu-west-1")
    lazy val snsClient = AmazonSNSAsyncClientBuilder.standard()
      .withCredentials(credentialsProviderChain(None, None))
      .withRegion(regionName)
      .build()
    lazy val anghammaradTopicARN: String = getString("scheduledDeployment.anghammaradTopicARN")
  }

  object credentials {
    def lookupSecret(service: String, id:String): Option[String] = getStringOpt(s"credentials.$service.$id")
  }

  object dynamoDb {
    lazy val regionName = getStringOpt("artifact.aws.region").getOrElse("eu-west-1")
    val client = AmazonDynamoDBAsyncClientBuilder.standard()
      .withCredentials(credentialsProviderChain(None, None))
      .withRegion(regionName)
      .withClientConfiguration(new ClientConfiguration())
      .build()
  }

  object freeze {
    private val formatter = ISODateTimeFormat.dateTime()
    lazy val startDate = getStringOpt("freeze.startDate").map(formatter.parseDateTime)
    lazy val endDate = getStringOpt("freeze.endDate").map(formatter.parseDateTime)
    lazy val message = getStringOpt("freeze.message").getOrElse("There is currently a change freeze. I'm not going to stop you, but you should think carefully about what you are about to do.")
    lazy val stages = getStringList("freeze.stages")
  }

  object housekeeping {
    lazy val summariseDeploysAfterDays = getIntOpt("housekeeping.summariseDeploysAfterDays").getOrElse(90)
    lazy val hour = getIntOpt("housekeeping.hour").getOrElse(4)
    lazy val minute = getIntOpt("housekeeping.minute").getOrElse(0)
    object tagOldArtifacts {
      lazy val hourOfDay = getIntOpt("housekeeping.tagOldArtifacts.hourOfDay").getOrElse(2)
      lazy val minuteOfHour = getIntOpt("housekeeping.tagOldArtifacts.minuteOfHour").getOrElse(0)

      lazy val enabled = getBooleanOpt("housekeeping.tagOldArtifacts.enabled").getOrElse(false)
      lazy val tagKey = getStringOpt("housekeeping.tagOldArtifacts.tagKey").getOrElse("housekeeping")
      lazy val tagValue = getStringOpt("housekeeping.tagOldArtifacts.tagValue").getOrElse("delete")
      // this should be a few days longer than the expiration age of the riffraff-builds bucket (28 days by default)
      //  so that it is less likely that a user will try and deploy a build that has since been removed
      lazy val minimumAgeDays = getIntOpt("housekeeping.tagOldArtifacts.minimumAgeDay").getOrElse(40)
      // the number to scan (we look at this number of most recent deploys to figure out what to keep, anything older
      // than this will not be considered)
      lazy val numberToScan = getIntOpt("housekeeping.tagOldArtifacts.numberToScan").getOrElse(50)
      // the number of artifacts to keep per stage
      lazy val numberToKeep = getIntOpt("housekeeping.tagOldArtifacts.numberToKeep").getOrElse(5)
    }
  }

  object logging {
    lazy val verbose = getStringOpt("logging").exists(_.equalsIgnoreCase("VERBOSE"))
    lazy val elkStreamName = getStringOpt("logging.elkStreamName")
    lazy val accessKey = getStringOpt("logging.aws.accessKey")
    lazy val secretKey = getStringOpt("logging.aws.secretKey")
    lazy val regionName = getStringOpt("logging.aws.region").getOrElse("eu-west-1")
    lazy val credentialsProvider = credentialsProviderChain(accessKey, secretKey)
  }

  object lookup {
    lazy val prismUrl = getStringOpt("lookup.prismUrl").getOrException("Prism URL not specified")
    lazy val timeoutSeconds = getIntOpt("lookup.timeoutSeconds").getOrElse(30)
  }

  object mongo {
    lazy val isConfigured = uri.isDefined
    lazy val uri = getStringOpt("mongo.uri")
    lazy val collectionPrefix = getStringOpt("mongo.collectionPrefix").getOrElse("")
  }

  object postgres {
    lazy val url = getStringOpt("postgres.uri")
    lazy val user = getStringOpt("postgres.user")
    lazy val password = getStringOpt("postgres.password")
  }

  object stages {
    lazy val order = getStringList("stages.order").filterNot(_ == "")
    lazy val ordering = UnnaturalOrdering(order, aliensAtEnd = false)
  }

  object artifact {
    object aws {
      implicit lazy val bucketName = getStringOpt("artifact.aws.bucketName").getOrException("Artifact bucket name not configured")
      lazy val accessKey = getStringOpt("artifact.aws.accessKey")
      lazy val secretKey = getStringOpt("artifact.aws.secretKey")
      lazy val credentialsProvider = credentialsProviderChain(accessKey, secretKey)
      lazy val regionName = getStringOpt("artifact.aws.region").getOrElse("eu-west-1")
      implicit lazy val client: AmazonS3 = AmazonS3ClientBuilder.standard()
        .withCredentials(credentialsProvider)
        .withRegion(regionName)
        .build()
    }
  }

  object build {
    lazy val pollingPeriodSeconds = getIntOpt("build.pollingPeriodSeconds").getOrElse(10)
    object aws {
      implicit lazy val bucketName = getString("build.aws.bucketName")
      lazy val accessKey = getStringOpt("build.aws.accessKey")
      lazy val secretKey = getStringOpt("build.aws.secretKey")
      lazy val credentialsProvider = credentialsProviderChain(accessKey, secretKey)
      lazy val regionName = getStringOpt("build.aws.region").getOrElse("eu-west-1")
      implicit lazy val client: AmazonS3 = AmazonS3ClientBuilder.standard()
        .withCredentials(credentialsProvider)
        .withRegion(regionName)
        .build()
    }
  }

  object tag {
    object aws {
      implicit lazy val bucketName = getStringOpt("tag.aws.bucketName")
      lazy val accessKey = getStringOpt("tag.aws.accessKey")
      lazy val secretKey = getStringOpt("tag.aws.secretKey")
      lazy val credentialsProvider = credentialsProviderChain(accessKey, secretKey)
      lazy val regionName = getStringOpt("tag.aws.region").getOrElse("eu-west-1")
      implicit lazy val client: AmazonS3 = AmazonS3ClientBuilder.standard()
        .withCredentials(credentialsProvider)
        .withRegion(regionName)
        .build()
    }
  }

  object deprecation {
    def pauseSeconds: Option[Int] = {
      val days = Days.daysBetween(new DateTime(2017,5,22,0,0,0), new DateTime()).getDays
      if (days > 0) Some(math.min(60, days)) else None
    }
  }

  def credentialsProviderChain(accessKey: Option[String], secretKey: Option[String]): AWSCredentialsProviderChain = {
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
      InstanceProfileCredentialsProvider.getInstance()
    )
  }

  def awsRegion(name: String): Region = RegionUtils.getRegion(name)

  object urls {
    lazy val publicPrefix: String = getStringOpt("urls.publicPrefix").getOrElse("http://localhost:9000")
  }

  val version:String = BuildInfo.buildNumber

  override def toString: String = configuration.toString
}

class Management(shutdownWhenInactive: ShutdownWhenInactive, deployments: Deployments) {
  val applicationName = "riff-raff"

  val pages = List(
    new BuildInfoPage,
    new HealthcheckManagementPage,
    new Switchboard(applicationName, shutdownWhenInactive.switch :: Healthcheck.switch :: deployments.enableSwitches),
    StatusPage(applicationName, Metrics.all),
    new LogbackLevelPage(applicationName)
  )
}

class BuildInfoPage extends ManagementPage {
  val path = "/management/manifest"
  def get(req: HttpRequest) = response
  lazy val response = PlainTextResponse(BuildInfo.toString)
}

object DeployMetrics extends Lifecycle {
  val runningDeploys = mutable.Buffer[UUID]()

  object DeployStart extends CountMetric("riffraff", "start_deploy", "Start deploy", "Number of deploys that are kicked off")
  object DeployComplete extends CountMetric("riffraff", "complete_deploy", "Complete deploy", "Number of deploys that completed", Some(DeployStart))
  object DeployFail extends CountMetric("riffraff", "fail_deploy", "Fail deploy", "Number of deploys that failed", Some(DeployStart))

  object DeployRunning extends GaugeMetric("riffraff", "running_deploys", "Running deploys", "Number of currently running deploys", () => runningDeploys.length)

  val all = Seq(DeployStart, DeployComplete, DeployFail, DeployRunning)

  val messageSub = DeployReporter.messages.subscribe(message => {
    message.stack.top match {
      case StartContext(Deploy(parameters)) =>
        DeployStart.recordCount(1)
        runningDeploys += message.context.deployId
      case FailContext(Deploy(parameters)) =>
        DeployFail.recordCount(1) // TODO this metric appears to be broken, never gets incremented
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
  val collectionStats = ScheduledAgent(5 seconds, 5 minutes, Map.empty[String, CollectionStats]) { _ =>  Persistence.store.collectionStats }
  def dataSize: Long = collectionStats().values.map(_.dataSize).foldLeft(0L)(_ + _)
  def storageSize: Long = collectionStats().values.map(_.storageSize).foldLeft(0L)(_ + _)
  def deployCollectionCount: Long = collectionStats().get(s"${Config.mongo.collectionPrefix}deployV2").map(_.documentCount).getOrElse(0L)
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
