package conf

import com.amazonaws.auth.profile.{
  ProfileCredentialsProvider => ProfileCredentialsProviderV1
}
import com.amazonaws.auth.{
  AWSCredentials => AWSCredentialsV1,
  AWSCredentialsProvider => AWSCredentialsProviderV1,
  AWSCredentialsProviderChain => AWSCredentialsProviderChainV1,
  BasicAWSCredentials => BasicAWSCredentialsV1,
  EnvironmentVariableCredentialsProvider => EnvironmentVariableCredentialsProviderV1,
  InstanceProfileCredentialsProvider => InstanceProfileCredentialsProviderV1,
  SystemPropertiesCredentialsProvider => SystemPropertiesCredentialsProviderV1
}
import com.typesafe.config.{Config => TypesafeConfig}
import controllers.Logging
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import controllers.routes
import software.amazon.awssdk.auth.credentials._
import software.amazon.awssdk.regions.internal.util.EC2MetadataUtils
import software.amazon.awssdk.regions.{Region, Region => AWSRegion}
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.{DescribeTagsRequest, Filter}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient
import utils.{DateFormats, UnnaturalOrdering}
import riffraff.BuildInfo
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.ssm.SsmClient

import scala.jdk.CollectionConverters._
import scala.util.{Success, Try}

class Config(configuration: TypesafeConfig, startTime: DateTime)
    extends Logging {

  private def getString(path: String): String = configuration.getString(path)
  private def getStringOpt(path: String): Option[String] = Try(
    configuration.getString(path)
  ).toOption
  private def getStringList(path: String): List[String] = getStringOpt(path)
    .map(_.split(",").map(_.trim).toList)
    .getOrElse(List.empty)
  private def getBooleanOpt(path: String): Option[Boolean] = Try(
    configuration.getBoolean(path)
  ).toOption
  private def getIntOpt(path: String): Option[Int] = Try(
    configuration.getInt(path)
  ).toOption

  implicit class RichOption[T](val option: Option[T]) {
    def getOrException(exceptionMessage: String): T = {
      option.getOrElse {
        throw new IllegalStateException(exceptionMessage)
      }
    }
  }

  private val defaultRegion: String = AWSRegion.EU_WEST_1.toString

  lazy val stage: String = {
    val theStage = Try(EC2MetadataUtils.getInstanceId) match {
      case Success(instanceId) if instanceId != null =>
        val request = DescribeTagsRequest
          .builder()
          .filters(
            Filter.builder().name("resource-type").values("instance").build(),
            Filter.builder().name("resource-id").values(instanceId).build()
          )
          .build()
        val ec2Client = Ec2Client
          .builder()
          .credentialsProvider(credentialsProviderChain(None, None))
          .build()
        try {
          val describeTagsResult = ec2Client.describeTags(request)
          describeTagsResult.tags.asScala
            .collectFirst { case t if t.key == "Stage" => t.value }
            .getOrException(
              "Couldn't find a Stage tag on the Riff Raff instance"
            )
        } finally {
          ec2Client.close()
        }
      case _ =>
        "DEV" // if we couldn't get an instance ID, we must be on a developer's machine
    }
    log.info(s"Riff Raff's stage = $theStage")
    theStage
  }

  object auth {
    lazy val domains: List[String] = getStringList("auth.domains")
    object allowList {
      lazy val useDatabase: Boolean =
        getBooleanOpt("auth.allowlist.useDatabase").getOrElse(false)
      lazy val addresses: List[String] = getStringList(
        "auth.allowlist.addresses"
      )
    }

    lazy val clientId: String =
      getStringOpt("auth.clientId").getOrException("No client ID configured")
    lazy val clientSecret: String =
      getStringOpt("auth.clientSecret").getOrException(
        "No client secret configured"
      )
    lazy val redirectUrl: String = getStringOpt("auth.redirectUrl").getOrElse(
      s"${urls.publicPrefix}${routes.Login.oauth2Callback.url}"
    )
    lazy val domain: String =
      getStringOpt("auth.domain").getOrException("No auth domain configured")
    lazy val superusers: List[String] = getStringList("auth.superusers")
    lazy val secretStateSupplierKeyName: String = getStringOpt(
      "auth.secretStateSupplier.keyName"
    ).getOrElse("/RiffRaff/PlayApplicationSecret")
    lazy val secretStateSupplierRegion: String =
      getStringOpt("auth.secretStateSupplier.region").getOrElse(defaultRegion)
    lazy val secretStateSupplierClient = SsmClient
      .builder()
      .credentialsProvider(credentialsProviderChain(None, None))
      .region(AWSRegion.of(secretStateSupplierRegion))
      .build()
  }

  object concurrency {
    lazy val maxDeploys = getIntOpt("concurrency.maxDeploys").getOrElse(8)
  }

  object continuousDeployment {
    lazy val enabled =
      getBooleanOpt("continuousDeployment.enabled").getOrElse(false)
  }

  object scheduledDeployment {
    lazy val enabled =
      getBooleanOpt("scheduledDeployment.enabled").getOrElse(false)
  }

  object credentials {
    lazy val regionName =
      getStringOpt("credentials.aws.region").getOrElse(defaultRegion)
    lazy val paramPrefix = getStringOpt("credentials.paramPrefix").getOrElse(
      s"/$stage/deploy/riff-raff/credentials"
    )
    lazy val credentialsProvider = credentialsProviderChain(None, None)
    lazy val stsClient = StsClient
      .builder()
      .region(AWSRegion.of(regionName))
      .credentialsProvider(credentialsProvider)
      .build()
  }

  object dynamoDb {
    lazy val regionName =
      getStringOpt("artifact.aws.region").getOrElse(defaultRegion)
    // Used by Scanamo which is not on the latest version of AWS SDK
    val client = DynamoDbClient
      .builder()
      .region(AWSRegion.of(regionName))
      .credentialsProvider(credentialsProviderChain(None, None))
      .build()
  }

  object freeze {
    private val formatter = ISODateTimeFormat.dateTime()
    lazy val startDate =
      getStringOpt("freeze.startDate").map(formatter.parseDateTime)
    lazy val endDate =
      getStringOpt("freeze.endDate").map(formatter.parseDateTime)
    lazy val message = getStringOpt("freeze.message").getOrElse(
      "There is currently a change freeze. I'm not going to stop you, but you should think carefully about what you are about to do."
    )
    lazy val stages = getStringList("freeze.stages")
  }

  object housekeeping {
    lazy val summariseDeploysAfterDays =
      getIntOpt("housekeeping.summariseDeploysAfterDays").getOrElse(90)
    lazy val hour = getIntOpt("housekeeping.hour").getOrElse(4)
    lazy val minute = getIntOpt("housekeeping.minute").getOrElse(0)
    object tagOldArtifacts {
      lazy val hourOfDay =
        getIntOpt("housekeeping.tagOldArtifacts.hourOfDay").getOrElse(2)
      lazy val minuteOfHour =
        getIntOpt("housekeeping.tagOldArtifacts.minuteOfHour").getOrElse(0)

      lazy val enabled =
        getBooleanOpt("housekeeping.tagOldArtifacts.enabled").getOrElse(false)
      lazy val tagKey =
        getStringOpt("housekeeping.tagOldArtifacts.tagKey").getOrElse(
          "housekeeping"
        )
      lazy val tagValue =
        getStringOpt("housekeeping.tagOldArtifacts.tagValue").getOrElse(
          "delete"
        )
      // this should be a few days longer than the expiration age of the riffraff-builds bucket (28 days by default)
      //  so that it is less likely that a user will try and deploy a build that has since been removed
      lazy val minimumAgeDays =
        getIntOpt("housekeeping.tagOldArtifacts.minimumAgeDay").getOrElse(40)
      // the number to scan (we look at this number of most recent deploys to figure out what to keep, anything older
      // than this will not be considered)
      lazy val numberToScan =
        getIntOpt("housekeeping.tagOldArtifacts.numberToScan").getOrElse(50)
      // the number of artifacts to keep per stage
      lazy val numberToKeep =
        getIntOpt("housekeeping.tagOldArtifacts.numberToKeep").getOrElse(5)
    }
  }

  object logging {
    lazy val verbose =
      getStringOpt("logging").exists(_.equalsIgnoreCase("VERBOSE"))
    lazy val accessKey = getStringOpt("logging.aws.accessKey")
    lazy val secretKey = getStringOpt("logging.aws.secretKey")
    lazy val regionName =
      getStringOpt("logging.aws.region").getOrElse(defaultRegion)
    lazy val credentialsProvider =
      credentialsProviderChain(accessKey, secretKey)
  }

  object lookup {
    lazy val prismUrl =
      getStringOpt("lookup.prismUrl").getOrException("Prism URL not specified")
    lazy val timeoutSeconds = getIntOpt("lookup.timeoutSeconds").getOrElse(30)
  }

  object postgres {
    lazy val url = getString("db.default.url")
    lazy val user = getString("db.default.user")
    lazy val hostname = getString("db.default.hostname")
    lazy val defaultPassword = getString("db.default.password")
  }

  object stages {
    lazy val order = getStringList("stages.order").filterNot(_ == "")
    lazy val ordering = UnnaturalOrdering(order, aliensAtEnd = false)
  }

  object artifact {
    object aws {
      implicit lazy val bucketName =
        getStringOpt("artifact.aws.bucketName").getOrException(
          "Artifact bucket name not configured"
        )
      lazy val accessKey = getStringOpt("artifact.aws.accessKey")
      lazy val secretKey = getStringOpt("artifact.aws.secretKey")
      lazy val credentialsProvider =
        credentialsProviderChain(accessKey, secretKey)
      lazy val regionName =
        getStringOpt("artifact.aws.region").getOrElse(defaultRegion)
      implicit lazy val client: S3Client = S3Client
        .builder()
        .credentialsProvider(credentialsProvider)
        .region(AWSRegion.of(regionName))
        .build()
    }
  }

  object build {
    lazy val pollingPeriodSeconds =
      getIntOpt("build.pollingPeriodSeconds").getOrElse(10)
    object aws {
      implicit lazy val bucketName = getString("build.aws.bucketName")
      lazy val accessKey = getStringOpt("build.aws.accessKey")
      lazy val secretKey = getStringOpt("build.aws.secretKey")
      lazy val credentialsProvider =
        credentialsProviderChain(accessKey, secretKey)
      lazy val regionName =
        getStringOpt("build.aws.region").getOrElse(defaultRegion)
      implicit lazy val client: S3Client = S3Client
        .builder()
        .credentialsProvider(credentialsProvider)
        .region(AWSRegion.of(regionName))
        .build()
    }
  }

  object tag {
    object aws {
      implicit lazy val bucketName: Option[String] = getStringOpt(
        "tag.aws.bucketName"
      )
      lazy val accessKey: Option[String] = getStringOpt("tag.aws.accessKey")
      lazy val secretKey: Option[String] = getStringOpt("tag.aws.secretKey")
      lazy val credentialsProvider: AwsCredentialsProvider =
        credentialsProviderChain(accessKey, secretKey)
      lazy val regionName: String =
        getStringOpt("tag.aws.region").getOrElse(defaultRegion)
      implicit lazy val client: S3Client = S3Client
        .builder()
        .credentialsProvider(credentialsProvider)
        .region(AWSRegion.of(regionName))
        .build()
    }
  }

  object management {
    object aws {
      lazy val regionName: String =
        getStringOpt("management.aws.region").getOrElse(defaultRegion)
      lazy val ec2Client: Ec2Client = Ec2Client
        .builder()
        .credentialsProvider(credentialsProviderChain(None, None))
        .region(AWSRegion.of(regionName))
        .build()
    }
  }

  object anghammarad {
    lazy val regionName: String =
      getStringOpt("anghammarad.region").getOrElse(defaultRegion)
    lazy val topicArn: String = getString("anghammarad.topic.arn")

    val snsClient: SnsAsyncClient =
      SnsAsyncClient
        .builder()
        .region(Region.of(regionName))
        .credentialsProvider(credentialsProviderChain(None, None))
        .build()
  }

  /** Credentials for use with V1 of the AWS SDK. Considered legacy as, where
    * possible, V2 of the AWS SDK should be used.
    *
    * @see
    *   [[credentialsProviderChain]]
    */
  def legacyCredentialsProviderChain(
      accessKey: Option[String] = None,
      secretKey: Option[String] = None
  ): AWSCredentialsProviderChainV1 = {
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

  def credentialsProviderChain(
      accessKey: Option[String],
      secretKey: Option[String]
  ): AwsCredentialsProvider = {
    val allProviders: List[AwsCredentialsProvider] = List(
      EnvironmentVariableCredentialsProvider.create(),
      SystemPropertyCredentialsProvider.create(),
      ProfileCredentialsProvider.create("deployTools"),
      InstanceProfileCredentialsProvider.create()
    )
    val providers: List[AwsCredentialsProvider] = (for {
      key <- accessKey
      secret <- secretKey
    } yield AwsBasicCredentials.create(key, secret)).fold(allProviders)(
      basicCreds =>
        basicCreds.asInstanceOf[AwsCredentialsProvider] +: allProviders
    )

    AwsCredentialsProviderChain
      .builder()
      .credentialsProviders(providers.asJava)
      .build()
  }

  object urls {
    lazy val publicPrefix: String =
      getStringOpt("urls.publicPrefix").getOrElse("http://localhost:9000")
  }

  val version: String = BuildInfo.buildNumber
  val startTimeString: String = DateFormats.Short.print(startTime)

  override def toString: String = configuration.toString
}
