package com.gu.deploy

import tasks._

trait PackageType {
  def name: String
  def pkg: Package

  def mkAction(actionName: String): Action = {
    if (actions.isDefinedAt(actionName)) {
      new Action {
        def resolve(host: Host) = actions(actionName)(host)
        def roles = pkg.roles
        def description = pkg.name + "." + actionName
        override def toString = "action " + description
      }
    } else {
      sys.error("Action %s is not supported on package %s of type %s" format (actionName, pkg.name, name))
    }
  }

  type ActionDefinition = PartialFunction[String, Host => List[Task]]
  val actions: ActionDefinition

  def defaultData: Map[String, String] = Map.empty
}

abstract class WebappPackageType extends PackageType {
  def containerName: String

  lazy val name = containerName + "-webapp"
  override lazy val defaultData = Map("port" -> "8080", "user" -> containerName)

  lazy val user = pkg.data("user")

  val actions: ActionDefinition = {
    case "deploy" => { host => List(
        BlockFirewall(host as user),
        CopyFile(host as user, pkg.srcDir.getPath, "/%s-apps/" format containerName),
        Restart(host as user, pkg.name, pkg.data("port")),
        UnblockFirewall(host as user)
      )
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
