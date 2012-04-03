package magenta

import tasks._
import net.liftweb.util.TimeHelpers._
import net.liftweb.json.JsonAST._
import net.liftweb.json.Implicits._


trait PackageType {
  def name: String
  def pkg: Package

  def mkAction(actionName: String): Action = {
    if (actions.isDefinedAt(actionName)) {
      new Action {
        def resolve(host: Host) = actions(actionName)(host)
        def apps = pkg.apps
        def description = pkg.name + "." + actionName
        override def toString = "action " + description
        override def equals(other: Any) = other match {
          case x : Action => x.description == description
          case _ => false
        }
      }
    } else {
      sys.error("Action %s is not supported on package %s of type %s" format (actionName, pkg.name, name))
    }
  }

  type ActionDefinition = PartialFunction[String, Host => List[Task]]
  def actions: ActionDefinition

  def defaultData: Map[String, JValue] = Map.empty
}

abstract class WebappPackageType extends PackageType {
  def containerName: String

  lazy val name = containerName + "-webapp"
  override def defaultData = Map[String, JValue]("port" -> "8080", "user" -> containerName, "servicename" -> pkg.name)

  lazy val user: String = pkg.stringData("user")
  lazy val port = pkg.stringData("port")
  lazy val serviceName = pkg.stringData("servicename")

  val actions: ActionDefinition = {
    case "deploy" => {
      host => { List(
        BlockFirewall(host as user),
        CopyFile(host as user, pkg.srcDir.getPath+"/", "/%s-apps/%s/" format (containerName, serviceName)),
        Restart(host as user, serviceName),
        WaitForPort(host, port, 1 minute),
        CheckUrls(host, port, pkg.arrayStringData("healthcheck_paths"), 20 seconds),
        UnblockFirewall(host as user))
      }
    }
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

  val actions: ActionDefinition = {
    case "deploy" => host => List(CopyFile(host, pkg.srcDir.getPath, "/"))
  }
}

case class DjangoWebappPackageType(pkg: Package) extends PackageType {
  lazy val name = "django-webapp"
  override lazy val defaultData = Map[String, JValue]("port" -> "80", "user" -> "django")

  lazy val user = pkg.stringData("user")
  lazy val port = pkg.stringData("port")
  lazy val appVersionPath = pkg.srcDir.listFiles().head

  val actions: ActionDefinition = {
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

  val actions: ActionDefinition = {
    case "hello" => host => List(
      SayHello(host)
    )

    case "echo-hello" => host => List(
      EchoHello(host)
    )
  }
}
