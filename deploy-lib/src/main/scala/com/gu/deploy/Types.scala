package com.gu.deploy

import tasks._

trait PackageType {
  def name: String

  def actions: Map[String, (String,Host) => List[Task]]

  def notimpl(pkgName: String, host: Host) = sys.error("not implemented")
}

case class JettyWebappPackageType() extends PackageType {
  val name = "jetty-webapp"

  lazy val actions = Map(
    "deploy" -> deployWebapp _
  )
  def deployWebapp(pkgName: String, host: Host) = {
    List(
      BlockFirewallTask(),
      CopyFileTask("packages/%s" format pkgName, "/jetty-apps/%s/" format pkgName ),
      RestartAndWaitTask(),
      UnblockFirewallTask()
    )
  }
}
case class FilePackageType() extends PackageType {
  val name = "file"
  lazy val actions = Map(
    "deploy" -> copyFiles _
  )

  def copyFiles(pkgName: String, host: Host) = {
    List(CopyFileTask("packages/%s" format (pkgName), "/"))
  }
}

object Types {
  lazy val packageTypes = Map(
    "jetty-webapp" -> new JettyWebappPackageType(),
    "file" -> new FilePackageType()
  )
}