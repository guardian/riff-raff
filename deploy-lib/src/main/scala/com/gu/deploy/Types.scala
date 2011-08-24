package com.gu.deploy

import tasks._

trait PackageType {
  def name: String
  def pkg: Package

  def mkAction(name: String): Option[Action] = PartialFunction.condOpt(name)(buildTasks) map { f =>
    new Action {
      def resolve(host: Host) = f(host)
      def roles = pkg.roles
      def description = pkg.pkgName + "." + name
      override def toString = "action " + description
    }
  }

  type TaskDefinition = PartialFunction[String, Host => List[Task]]
  val buildTasks: TaskDefinition
}

case class JettyWebappPackageType(pkg: Package) extends PackageType {
  val name = "jetty-webapp"

  val buildTasks: TaskDefinition = {
    case "deploy" => { host => deployWebapp(pkg.pkgName, host) }
  }

  def deployWebapp(packageName: String, host: Host) = List(
        BlockFirewallTask(),
        CopyFileTask("packages/%s" format packageName, "/jetty-apps/%s/" format packageName),
        RestartAndWaitTask(),
        UnblockFirewallTask()
      )
}


case class FilePackageType(pkg: Package) extends PackageType {
  val name = "file"

  val buildTasks: TaskDefinition = {
    case "deploy" => host => List(CopyFileTask("packages/%s" format (pkg.pkgName), "/"))
  }

}
