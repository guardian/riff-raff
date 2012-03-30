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
        def resolve(project: Project) = perAppActions(actionName)(project)
      }

    else sys.error("Action %s is not supported on package %s of type %s" format (actionName, pkg.name, name))
  }

  type HostActionDefinition = PartialFunction[String, Host => List[Task]]
  def perHostActions: HostActionDefinition = Map.empty

  type AppActionDefinition = PartialFunction[String, Project => List[Task]]
  def perAppActions: AppActionDefinition = Map.empty

  def defaultData: Map[String, JValue] = Map.empty
}

private abstract class PackageAction(pkg: Package, actionName: String) extends Action {
  def apps = pkg.apps
  def description = pkg.name + "." + actionName
}

abstract class WebappPackageType extends PackageType {
  def containerName: String

  lazy val name = containerName + "-webapp"
  override def defaultData = Map[String, JValue]("port" -> "8080",
    "user" -> containerName,
    "servicename" -> pkg.name,
    "bucket" -> "artifacts-8356e7"
  )

  lazy val user: String = pkg.stringData("user")
  lazy val port = pkg.stringData("port")
  lazy val serviceName = pkg.stringData("servicename")
  lazy val packageArtifactDir = pkg.srcDir.getPath + "/"
  lazy val bucket = pkg.stringData("bucket")

  override val perHostActions: HostActionDefinition = {
    case "deploy" => {
      host => {
        List(
        BlockFirewall(host as user),
        CopyFile(host as user, packageArtifactDir, "/%s-apps/%s/" format (containerName, serviceName)),
        Restart(host as user, serviceName),
        WaitForPort(host, port, 1 minute),
        CheckUrls(host, port, pkg.arrayStringData("healthcheck_paths"), 20 seconds),
        UnblockFirewall(host as user))
      }
    }
  }

  override val perAppActions: AppActionDefinition = {
    case "uploadArtifacts" => project =>
      List(
        S3Upload(project.stage, bucket, new File(packageArtifactDir))
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
