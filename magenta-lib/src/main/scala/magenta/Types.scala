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
  override lazy val defaultData = Map[String, JValue]("port" -> "8080", "user" -> containerName)

  lazy val user: String = pkg.stringData("user")

  val actions: ActionDefinition = {
    case "deploy" => {
      host => { List(
        BlockFirewall(host as user),
        CopyFile(host as user, pkg.srcDir.getPath, "/%s-apps/" format containerName),
        Restart(host as user, pkg.name),
        WaitForPort(host, pkg.stringData("port"), 20 seconds),
        CheckUrls(host, pkg.stringData("port"), pkg.arrayStringData("healthcheck_paths"), 20 seconds),
        UnblockFirewall(host as user))
      }
    }
  }
}

case class DjangoWebappPackageType(pkg: Package) extends PackageType {
  lazy val name = "django-webapp"
  override lazy val defaultData = Map[String, JValue]("port" -> "80", "user" -> "django")

  lazy val user = pkg.stringData("user")
  lazy val port = pkg.stringData("port")
  lazy val appdir = pkg.srcDir.getPath.split("/").last

  val actions: ActionDefinition = {
    case "deploy" => { host => {
      val destDir: String = "/django-apps/%s" format appdir
      List(
        BlockFirewall(host as user),
        SetSwitch(host, port, "HEALTHCHECK_OK", false),
        CopyFile(host as user, pkg.srcDir.getPath, destDir),
        LinkFile(host as user, destDir, "/django-apps/%s" format pkg.name),
        Restart(host as user, pkg.name),
        WaitForPort(host, port, 20 seconds),
        SetSwitch(host, port, "HEALTHCHECK_OK", true),
        UnblockFirewall(host as user)

        //        BlockFirewall(host as user),
        //        CopyFile(host as user, pkg.srcDir.getPath, "/%s-apps/" format "django"),
        //        Restart(host as user, pkg.name),
        //        WaitForPort(host, pkg.data("port"), 20 seconds),
        //        UnblockFirewall(host as user)
      )
    }
    }
  }
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
