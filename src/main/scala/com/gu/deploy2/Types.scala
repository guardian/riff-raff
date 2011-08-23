package com.gu.deploy2

trait PackageType {
  def actions: Map[String, Host => List[Task]]

  def notimpl(host: Host) = sys.error("not implemented")
}

case class JettyWebappPackageType() extends PackageType {
  lazy val actions = Map(
    "deploy" -> notimpl _,
    "install" -> notimpl _,
    "unblock" -> notimpl _,
    "restart" -> notimpl _,
    "block" -> notimpl _
  )

}
case class FilePackageType() extends PackageType {

  lazy val actions = Map(
    "deploy" -> copyFiles _
  )

  def copyFiles(host: Host) = {
    List(CopyFileTask("packages/%s/*" format ("appname"), "/"))
  }
}

object Types {
  lazy val packageTypes = Map(
    "jetty-webapp" -> new JettyWebappPackageType(),
    "file" -> new FilePackageType()
  )
}