package com.gu.deploy

import tasks._

trait PackageType {
  def name: String

  def mkAction(pkg: Package, name: String): Option[Action] = buildTasks(pkg, name) map { f =>
    new Action {
      def resolve(host: Host) = f(host)
      def roles = pkg.roles
      def description = pkg.pkgName + "." + name
      override def toString = "action " + description
    }
  }

  def buildTasks(pkg: Package, actionName: String): Option[Host => List[Task]]
}

case class JettyWebappPackageType() extends PackageType {
  val name = "jetty-webapp"

  def buildTasks(pkg: Package, name: String): Option[Host => List[Task]] = name match {
    case "deploy" => Some({ host: Host => deployWebapp(pkg.pkgName, host) })
    case _ => None
  }

  def deployWebapp(packageName: String, host: Host) = List(
        BlockFirewallTask(),
        CopyFileTask("packages/%s" format packageName, "/jetty-apps/%s/" format packageName),
        RestartAndWaitTask(),
        UnblockFirewallTask()
      )
}


case class FilePackageType() extends PackageType {
  val name = "file"

  def buildTasks(pkg: Package, name: String): Option[Host => List[Task]] = name match {
    case "deploy" => Some({host: Host =>
      List(CopyFileTask("packages/%s" format (pkg.pkgName), "/"))
    }
    )

    case _ => None
  }

}

object Types {
  lazy val packageTypes = Map(
    "jetty-webapp" -> new JettyWebappPackageType(),
    "file" -> new FilePackageType()
  )
}