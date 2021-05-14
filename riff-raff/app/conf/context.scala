package conf

import java.util.UUID

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.profile.{ProfileCredentialsProvider => ProfileCredentialsProviderV1}
import com.amazonaws.auth.{AWSCredentials => AWSCredentialsV1, AWSCredentialsProvider => AWSCredentialsProviderV1, AWSCredentialsProviderChain => AWSCredentialsProviderChainV1, BasicAWSCredentials => BasicAWSCredentialsV1, EnvironmentVariableCredentialsProvider => EnvironmentVariableCredentialsProviderV1, InstanceProfileCredentialsProvider => InstanceProfileCredentialsProviderV1, SystemPropertiesCredentialsProvider => SystemPropertiesCredentialsProviderV1}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder
import com.amazonaws.services.sns.{AmazonSNSAsyncClientBuilder => AmazonSNSAsyncClientBuilderV1}
import com.amazonaws.services.rds.auth.{GetIamAuthTokenRequest, RdsIamAuthTokenGenerator}
import com.gu.management._
import com.gu.management.logback.LogbackLevelPage
import com.typesafe.config.{Config => TypesafeConfig}
import controllers.{Logging, routes}
import deployment.Deployments
import deployment.actors.DeployMetricsActor
import lifecycle.{Lifecycle, ShutdownWhenInactive}
import magenta.ContextMessage._
import magenta.Message._
import magenta._
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, Days}
import persistence.{CollectionStats, DataStore}
import play.api.Configuration
import riffraff.BuildInfo
import software.amazon.awssdk.auth.credentials._
import software.amazon.awssdk.regions.internal.util.EC2MetadataUtils
import software.amazon.awssdk.regions.{Region => AWSRegion}
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.{DescribeTagsRequest, Filter}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient
import utils.{DateFormats, PeriodicScheduledAgentUpdate, ScheduledAgent, UnnaturalOrdering}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Success, Try}

class Config(configuration: TypesafeConfig, startTime: DateTime) extends Logging {

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
        val request = DescribeTagsRequest.builder().filters(
          Filter.builder().name("resource-type").values("instance").build(),
          Filter.builder().name("resource-id").values(instanceId).build()
        ).build()
        val ec2Client = Ec2Client.builder()
          .credentialsProvider(credentialsProviderChain(None, None))
          .build()
        try {
          val describeTagsResult = ec2Client.describeTags(request)
          describeTagsResult.tags.asScala
            .collectFirst{ case t if t.key == "Stage" => t.value }
            .getOrException("Couldn't find a Stage tag on the Riff Raff instance")
        } finally {
          ec2Client.close()
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
    // For compatibility reasons this need to use the old AWS SDK
    lazy val snsClient = AmazonSNSAsyncClientBuilderV1.standard()
      .withCredentials(credentialsProviderChainV1(None, None))
      .withRegion(regionName)
      .build()
    lazy val anghammaradTopicARN: String = getString("scheduledDeployment.anghammaradTopicARN")
  }

  object credentials {
    lazy val regionName = getStringOpt("credentials.aws.region").getOrElse("eu-west-1")
    lazy val paramPrefix = getStringOpt("credentials.paramPrefix").getOrElse(s"/$stage/deploy/riff-raff/credentials")
    lazy val credentialsProvider = credentialsProviderChain(None, None)
    lazy val stsClient = StsClient.builder().region(AWSRegion.of(regionName)).credentialsProvider(credentialsProvider).build()
  }

  object dynamoDb {
    lazy val regionName = getStringOpt("artifact.aws.region").getOrElse("eu-west-1")
    // Used by Scanamo which is not on the latest version of AWS SDK
    val client = AmazonDynamoDBAsyncClientBuilder.standard()
      .withCredentials(credentialsProviderChainV1(None, None))
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
    lazy val credentialsProvider = credentialsProviderChainV1(accessKey, secretKey)
  }

  object lookup {
    lazy val prismUrl = getStringOpt("lookup.prismUrl").getOrException("Prism URL not specified")
    lazy val timeoutSeconds = getIntOpt("lookup.timeoutSeconds").getOrElse(30)
  }

  object postgres {
    lazy val url = getString("db.default.url")
    lazy val user =  getString("db.default.user")
    lazy val hostname = getString("db.default.hostname")
    lazy val defaultPassword = getString("db.default.password")
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
      implicit lazy val client: S3Client = S3Client.builder()
        .credentialsProvider(credentialsProvider)
        .region(AWSRegion.of(regionName))
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
      implicit lazy val client: S3Client = S3Client.builder()
        .credentialsProvider(credentialsProvider)
        .region(AWSRegion.of(regionName))
        .build()
    }
  }

  object tag {
    object aws {
      implicit lazy val bucketName: Option[String] = getStringOpt("tag.aws.bucketName")
      lazy val accessKey: Option[String] = getStringOpt("tag.aws.accessKey")
      lazy val secretKey: Option[String] = getStringOpt("tag.aws.secretKey")
      lazy val credentialsProvider: AwsCredentialsProvider = credentialsProviderChain(accessKey, secretKey)
      lazy val regionName: String = getStringOpt("tag.aws.region").getOrElse("eu-west-1")
      implicit lazy val client: S3Client = S3Client.builder()
        .credentialsProvider(credentialsProvider)
        .region(AWSRegion.of(regionName))
        .build()
    }
  }


  def credentialsProviderChainV1(accessKey: Option[String] = None, secretKey: Option[String] = None): AWSCredentialsProviderChainV1 = {
    new AWSCredentialsProviderChainV1(
      new AWSCredentialsProviderV1 {
        override def getCredentials: AWSCredentialsV1 = (for {
          key <- accessKey
          secret <- secretKey
        } yield new BasicAWSCredentialsV1(key, secret)).orNull
        override def refresh(): Unit = {}
      },
      new EnvironmentVariableCredentialsProviderV1,
      new SystemPropertiesCredentialsProviderV1,
      new ProfileCredentialsProviderV1("deployTools"),
      InstanceProfileCredentialsProviderV1.getInstance()
    )
  }

  def credentialsProviderChain(accessKey: Option[String], secretKey: Option[String]): AwsCredentialsProvider = {
    val allProviders: List[AwsCredentialsProvider] = List(
      EnvironmentVariableCredentialsProvider.create(),
      SystemPropertyCredentialsProvider.create(),
      ProfileCredentialsProvider.create("deployTools"),
      InstanceProfileCredentialsProvider.create()
    )
    val providers: List[AwsCredentialsProvider] = (for {
      key <- accessKey
      secret <- secretKey
    } yield AwsBasicCredentials.create(key, secret)).fold(allProviders)(basicCreds => basicCreds.asInstanceOf[AwsCredentialsProvider] +: allProviders)

    AwsCredentialsProviderChain.builder().credentialsProviders(providers.asJava).build()
  }

  object urls {
    lazy val publicPrefix: String = getStringOpt("urls.publicPrefix").getOrElse("http://localhost:9000")
  }

  val version:String = BuildInfo.buildNumber
  val startTimeString:String = DateFormats.Short.print(startTime)

  override def toString: String = configuration.toString
}

class Management(config: Config, shutdownWhenInactive: ShutdownWhenInactive, deployments: Deployments, datastore: DataStore) {
  val applicationName = "riff-raff"

  val pages = List(
    new BuildInfoPage,
    new HealthcheckManagementPage,
    new Switchboard(applicationName, shutdownWhenInactive.switch :: Healthcheck.switch :: deployments.enableSwitches),
    StatusPage(applicationName, new Metrics(config, datastore).all),
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

object DatastoreRequest extends TimingMetric(
  "performance",
  "database_requests",
  "Database requests",
  "outgoing requests to the database"
)

class DatastoreMetrics(config: Config, datastore: DataStore) {
  val update: PeriodicScheduledAgentUpdate[Map[String, CollectionStats]] = PeriodicScheduledAgentUpdate(5 seconds, 5 minutes){ _ => datastore.collectionStats }
  val collectionStats = ScheduledAgent(Map.empty[String, CollectionStats], update)

  def dataSize: Long = collectionStats().values.map(_.dataSize).foldLeft(0L)(_ + _)
  def storageSize: Long = collectionStats().values.map(_.storageSize).foldLeft(0L)(_ + _)
  def deployCollectionCount: Long = collectionStats().get("deploy").map(_.documentCount).getOrElse(0L)
  object PostgresDataSize extends GaugeMetric("postgres", "data_size", "PostgreSQL data size", "The size of the data held in postgres tables", () => dataSize)
  object PostgresStorageSize extends GaugeMetric("postgres", "storage_size", "PostgreSQL storage size", "The size of the storage used by the PostgreSQL tables", () => storageSize)
  object PostgresDeployCollectionCount extends GaugeMetric("postgres", "deploys_collection_count", "Deploys collection count", "The number of rows in the deploys table", () => deployCollectionCount)
  val all = Seq(DatastoreRequest, PostgresDataSize, PostgresStorageSize, PostgresDeployCollectionCount)
}

object LoginCounter extends CountMetric("webapp",
  "login_attempts",
  "Login attempts",
  "Number of attempted logins")

object FailedLoginCounter extends CountMetric("webapp",
  "failed_logins",
  "Failed logins",
  "Number of failed logins")

class Metrics(config: Config, datastore: DataStore) {
  val all: Seq[Metric] =
    magenta.metrics.MagentaMetrics.all ++
    Seq(LoginCounter, FailedLoginCounter) ++
    //PlayRequestMetrics.asMetrics ++
    DeployMetrics.all ++
    new DatastoreMetrics(config, datastore).all ++
    TaskMetrics.all
}
