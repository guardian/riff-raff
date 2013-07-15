package magenta

import tasks._
import net.liftweb.util.TimeHelpers._
import net.liftweb.json.JsonAST._
import net.liftweb.json.Implicits._
import java.io.File
import scala.PartialFunction

trait PackageType {
  def name: String
  def pkg: Package

  def mkAction(actionName: String): Action = {

    if (perHostActions.isDefinedAt(actionName))
      new PackageAction(pkg, actionName)  {
        def resolve(deployInfo: DeployInfo, parameters: DeployParameters) = {
          val hostsForApps = deployInfo.hosts.filter(h => (h.apps intersect apps).nonEmpty)
          hostsForApps flatMap (perHostActions(actionName)(_))
        }
      }

    else if (perAppActions.isDefinedAt(actionName))
      new PackageAction(pkg, actionName) {
        def resolve(deployInfo: DeployInfo, parameters: DeployParameters) =
          perAppActions(actionName)(deployInfo, parameters)
      }

    else sys.error("Action %s is not supported on package %s of type %s" format (actionName, pkg.name, name))
  }

  type HostActionDefinition = PartialFunction[String, Host => List[Task]]
  def perHostActions: HostActionDefinition = Map.empty

  type AppActionDefinition = PartialFunction[String, (DeployInfo, DeployParameters) => List[Task]]
  def perAppActions: AppActionDefinition = Map.empty

  def defaultData: Map[String, JValue] = Map.empty
}

private abstract case class PackageAction(pkg: Package, actionName: String) extends Action {
  def apps = pkg.apps
  def description = pkg.name + "." + actionName
}

case class AmazonWebServicesS3(pkg: Package) extends PackageType {
  val name = "aws-s3"

  override val defaultData = Map[String, JValue](
    "prefixStage" -> true,
    "prefixPackage" -> true
  )

  lazy val staticDir = pkg.srcDir.getPath + "/"

  lazy val prefixStage = pkg.booleanData("prefixStage")
  lazy val prefixPackage = pkg.booleanData("prefixPackage")

  //required configuration, you cannot upload without setting these
  lazy val bucket = pkg.stringDataOption("bucket")
  lazy val bucketResource = pkg.stringDataOption("bucketResource")

  lazy val cacheControl = pkg.stringDataOption("cacheControl")
  lazy val cacheControlPatterns = cacheControl.map(cc => List(PatternValue(".*", cc))).getOrElse(pkg.arrayPatternValueData("cacheControl"))

  override val perAppActions: AppActionDefinition = {
    case "uploadStaticFiles" => (deployInfo, parameters) =>
      assert(bucket.isDefined != bucketResource.isDefined, "One, and only one, of bucket or bucketResource must be specified")
      val bucketName = bucket.orElse {
        assert(pkg.apps.size == 1, s"The $name package type, in conjunction with bucketResource, cannot be used when more than one app is specified")
        bucketResource.map{ resource =>
          val data = deployInfo.firstMatchingData(resource, pkg.apps.head, parameters.stage.name)
          assert(data.isDefined, s"Cannot find resource value for $resource (${pkg.apps.head} in ${parameters.stage.name})")
          data.get
        }.map(_.value)
      }
      List(
        S3Upload(parameters.stage,
          bucketName.get,
          new File(staticDir),
          cacheControlPatterns,
          prefixStage = prefixStage,
          prefixPackage = prefixPackage
        )
      )
  }
}

case class AutoScaling(pkg: Package) extends PackageType {
  val name = "auto-scaling-with-ELB"

  override val defaultData = Map[String, JValue](
    "secondsToWait" -> 15 * 60,
    "healthcheckGrace" -> 0
  )

  lazy val packageArtifactDir = pkg.srcDir.getPath + "/"
  lazy val bucket = pkg.stringData("bucket")

  override val perAppActions: AppActionDefinition = {
    case "deploy" => (_, parameters) => {
      List(
        CheckGroupSize(pkg.name, parameters.stage),
        SuspendAlarmNotifications(pkg.name, parameters.stage),
        TagCurrentInstancesWithTerminationTag(pkg.name, parameters.stage),
        DoubleSize(pkg.name, parameters.stage),
        WaitForStabilization(pkg.name, parameters.stage, pkg.intData("secondsToWait").toInt * 1000),
        HealthcheckGrace(pkg.intData("healthcheckGrace").toInt * 1000),
        WaitForStabilization(pkg.name, parameters.stage, pkg.intData("secondsToWait").toInt * 1000),
        CullInstancesWithTerminationTag(pkg.name, parameters.stage),
        WaitForStabilization(pkg.name, parameters.stage, pkg.intData("secondsToWait").toInt * 1000),
        ResumeAlarmNotifications(pkg.name, parameters.stage)
      )
    }
    case "uploadArtifacts" => (_, parameters) =>
      List(
      S3Upload(parameters.stage, bucket, new File(packageArtifactDir))
    )
  }
}

case class ElasticSearch(pkg: Package) extends PackageType {
  val name = "elastic-search"

  override val defaultData = Map[String, JValue](
    "secondsToWait" -> 15 * 60
  )

  lazy val packageArtifactDir = pkg.srcDir.getPath + "/"
  lazy val bucket = pkg.stringData("bucket")

  override val perAppActions: AppActionDefinition = {
    case "deploy" => (_, parameters) => {
      List(
        TagCurrentInstancesWithTerminationTag(pkg.name, parameters.stage),
        DoubleSize(pkg.name, parameters.stage),
        WaitForElasticSearchClusterGreen(pkg.name, parameters.stage, pkg.intData("secondsToWait").toInt * 1000),
        CullElasticSearchInstancesWithTerminationTag(pkg.name, parameters.stage, pkg.intData("secondsToWait").toInt * 1000)
      )
    }
    case "uploadArtifacts" => (_, parameters) =>
      List(
        S3Upload(parameters.stage, bucket, new File(packageArtifactDir))
      )
  }
}

abstract class WebappPackageType extends PackageType {
  def containerName: String

  lazy val name = containerName + "-webapp"
  override def defaultData = Map[String, JValue]("port" -> "8080",
    "user" -> containerName,
    "servicename" -> pkg.name,
    "waitseconds" -> 60,
    "checkseconds" -> 120,
    "checkUrlReadTimeoutSeconds" -> 5,
    "copyRoots" -> JArray(List("")),
    "copyMode" -> "additive"
  )

  lazy val user: String = pkg.stringData("user")
  lazy val port = pkg.stringData("port")
  lazy val serviceName = pkg.stringData("servicename")
  lazy val packageArtifactDir = pkg.srcDir.getPath
  lazy val bucket = pkg.stringData("bucket")
  lazy val waitDuration = pkg.intData("waitseconds").toLong.seconds
  lazy val checkDuration = pkg.intData("checkseconds").toLong.seconds
  lazy val healthCheckPaths = {
    val paths = pkg.arrayStringData("healthcheck_paths")
    if (paths.isEmpty) List("/%s/management/healthcheck" format serviceName)
    else paths
  }
  lazy val checkUrlReadTimeoutSeconds = pkg.intData("checkUrlReadTimeoutSeconds").toInt
  val TRAILING_SLASH = """^(.*/)$""".r
  lazy val copyRoots = pkg.arrayStringData("copyRoots").map{ root =>
    root match {
      case "" => root
      case TRAILING_SLASH(withSlash) => withSlash
      case noTrailingSlash => s"$noTrailingSlash/"
    }
  }
  lazy val copyMode = pkg.stringData("copyMode")

  override val perHostActions: HostActionDefinition = {
    case "deploy" => {
      host => {
        BlockFirewall(host as user) ::
        copyRoots.map(root => CopyFile(host as user, s"$packageArtifactDir/$root", s"/$containerName-apps/$serviceName/$root", copyMode)) :::
        Restart(host as user, serviceName) ::
        WaitForPort(host, port, waitDuration) ::
        CheckUrls(host, port, healthCheckPaths, checkDuration, checkUrlReadTimeoutSeconds) ::
        UnblockFirewall(host as user) ::
        Nil
      }
    }
    case "restart" => {
      host => {
        List(
          BlockFirewall(host as user),
          Restart(host as user, serviceName),
          WaitForPort(host, port, waitDuration),
          CheckUrls(host, port, healthCheckPaths, checkDuration, checkUrlReadTimeoutSeconds),
          UnblockFirewall(host as user)
        )
      }
    }
  }

  override val perAppActions: AppActionDefinition = {
    case "uploadArtifacts" => (_, parameters) =>
      List(
        S3Upload(parameters.stage, bucket, new File(packageArtifactDir))
      )
  }
}

case class ExecutableJarWebappPackageType(pkg: Package) extends WebappPackageType {
  override def defaultData = super.defaultData + ("user" -> "jvmuser")
  val containerName = "executable-jar"
}

case class JettyWebappPackageType(pkg: Package) extends WebappPackageType {
  val containerName = "jetty"
}

case class ResinWebappPackageType(pkg: Package) extends WebappPackageType {
  val containerName = "resin"
}


case class FilePackageType(pkg: Package) extends PackageType {
  val name = "file"

  override val perHostActions: HostActionDefinition = {
    case "deploy" => host => List(CopyFile(host, pkg.srcDir.getPath, "/"))
  }
}

case class DjangoWebappPackageType(pkg: Package) extends PackageType {
  lazy val name = "django-webapp"
  override lazy val defaultData = Map[String, JValue]("port" -> "80", "user" -> "django")

  lazy val user = pkg.stringData("user")
  lazy val port = pkg.stringData("port")

  // During preview the pkg.srcDir is not available, so we have to be a bit funky with options
  lazy val appVersionPath = Option(pkg.srcDir.listFiles()).flatMap(_.headOption)

  override val perHostActions: HostActionDefinition = {
    case "deploy" => { host => {
      val destDir: String = "/django-apps/"
      List(
        BlockFirewall(host as user),
        CompressedCopy(host as user, appVersionPath, destDir),
        Link(host as user, appVersionPath.map(destDir + _.getName), "/django-apps/%s" format pkg.name),
        ApacheGracefulRestart(host as user),
        WaitForPort(host, port, 1 minute),
        UnblockFirewall(host as user)
      )
    }
    }
  }
}

case class DemoPackageType(pkg: Package) extends PackageType {
  val name = "demo"

  override val perHostActions: HostActionDefinition = {
    case "hello" => host => List(
      SayHello(host)
    )

    case "echo-hello" => host => List(
      EchoHello(host)
    )
  }
}
