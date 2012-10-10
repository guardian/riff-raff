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
      new PackageAction(pkg, actionName) with PerHostAction {
        def resolve(host: Host) = perHostActions(actionName)(host)
      }

    else if (perAppActions.isDefinedAt(actionName))
      new PackageAction(pkg, actionName) with PerAppAction {
        def resolve( stage: Stage) = perAppActions(actionName)(stage)
      }

    else sys.error("Action %s is not supported on package %s of type %s" format (actionName, pkg.name, name))
  }

  type HostActionDefinition = PartialFunction[String, Host => List[Task]]
  def perHostActions: HostActionDefinition = Map.empty

  type AppActionDefinition = PartialFunction[String, Stage => List[Task]]
  def perAppActions: AppActionDefinition = Map.empty

  def defaultData: Map[String, JValue] = Map.empty
}

case class AmazonWebServicesS3(pkg: Package) extends PackageType {
  val name = "aws-s3"

  lazy val staticDir = pkg.srcDir.getPath + "/"

  //required configuration, you cannot upload without setting these
  lazy val bucket = pkg.stringData("bucket")
  lazy val cacheControl = pkg.stringData("cacheControl")

  override val perAppActions: AppActionDefinition = {
    case "uploadStaticFiles" => stage =>
      List(
        S3Upload(stage, bucket, new File(staticDir), Some(cacheControl))
      )
  }
}

case class AutoScalingWithELB(pkg: Package) extends PackageType {
  val name = "auto-scaling-with-ELB"

  override val defaultData = Map[String, JValue]("secondsToWait" -> 5 * 60)

  lazy val bucket = pkg.stringData("bucket")
  lazy val packageArtifactDir = pkg.srcDir.getPath + "/"

  override val perAppActions: AppActionDefinition = {
    case "deploy" => stage => {
      List(
        S3Upload(stage, bucket, new File(packageArtifactDir)),
        DoubleSize(pkg.name, stage),
        WaitTillUpAndInELB("app", Stage("PROD"), pkg.intData("secondsToWait").toInt * 1000)
      )
    }
  }
}

private abstract case class PackageAction(pkg: Package, actionName: String) extends Action {
  def apps = pkg.apps
  def description = pkg.name + "." + actionName
}

abstract class WebappPackageType extends PackageType {
  def containerName: String

  lazy val name = containerName + "-webapp"
  override def defaultData = Map[String, JValue]("port" -> "8080",
    "user" -> containerName,
    "servicename" -> pkg.name,
    "waitseconds" -> 60,
    "checkseconds" -> 120
  )

  lazy val user: String = pkg.stringData("user")
  lazy val port = pkg.stringData("port")
  lazy val serviceName = pkg.stringData("servicename")
  lazy val packageArtifactDir = pkg.srcDir.getPath + "/"
  lazy val bucket = pkg.stringData("bucket")
  lazy val waitDuration = pkg.intData("waitseconds").toLong.seconds
  lazy val checkDuration = pkg.intData("checkseconds").toLong.seconds
  lazy val healthCheckPaths = {
    val paths = pkg.arrayStringData("healthcheck_paths")
    if (paths.isEmpty) List("/%s/management/healthcheck" format serviceName)
    else paths
  }

  override val perHostActions: HostActionDefinition = {
    case "deploy" => {
      host => {
        List(
        BlockFirewall(host as user),
        CopyFile(host as user, packageArtifactDir, "/%s-apps/%s/" format (containerName, serviceName)),
        Restart(host as user, serviceName),
        WaitForPort(host, port, waitDuration),
        CheckUrls(host, port, healthCheckPaths, checkDuration),
        UnblockFirewall(host as user))
      }
    }
  }

  override val perAppActions: AppActionDefinition = {
    case "uploadArtifacts" => stage =>
      List(
        S3Upload(stage, bucket, new File(packageArtifactDir))
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
  lazy val appVersionPath = pkg.srcDir.listFiles().head

  override val perHostActions: HostActionDefinition = {
    case "deploy" => { host => {
      val destDir: String = "/django-apps/"
      List(
        BlockFirewall(host as user),
        CopyFile(host as user, appVersionPath.getPath, destDir),
        ApacheGracefulStop(host as user),
        Link(host as user, destDir + appVersionPath.getName, "/django-apps/%s" format pkg.name),
        ApacheStart(host as user),
        WaitForPort(host, port, 1 minute),
        UnblockFirewall(host as user)
      )
    }
    }
  }
}

case class PuppetPackageType(pkg: Package) extends PackageType {
  lazy val name = "puppet"

  override def defaultData = super.defaultData ++ Seq[(String, JValue)](
    ("user" -> "puppet"),
    ("path" -> "/var/lib/puppet/deploy"),
    ("link" -> "/etc/puppet/site")
  )

  lazy val artifact = pkg.stringData("artifact")
  lazy val bucket = pkg.stringData("bucket")

  lazy val user: String = pkg.stringData("user")
  lazy val path: String = pkg.stringData("path")
  lazy val link: String = pkg.stringData("link")

  override val perHostActions: HostActionDefinition = {
    case "applyPuppet" => host => {
      val artifact_local = "%s/%s".format(pkg.srcDir.getPath, artifact)
      val artifact_remote_directory = "%s/%s".format(path, System.currentTimeMillis)
      val artifact_remote = "%s/%s".format(artifact_remote_directory, artifact)

      List(
        Mkdir(host as user, artifact_remote_directory),
        CopyFile(host as user, artifact_local, artifact_remote),
        Unzip(host as user, artifact_remote, artifact_remote_directory),
        Link(host as user, artifact_remote_directory + "/puppet", link),
        Puppet(host as user,
          modulePath = link + "/modules",
          fileserverConfiguration = link + "/fileserver.conf",
          templateDirectory = link + "/templates",
          manifest = link + "/site.pp"
        )
      )
    }
  }

  override val perAppActions: AppActionDefinition = {
    case "uploadArtifacts" => stage =>
      List(
        S3Upload(stage, bucket, new File(pkg.srcDir.getPath + "/"))
      )
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
