package com.gu.deploy2

trait PackageType {
  def name: String

  def actions: Map[String, (String,Host) => List[Task]]

  def notimpl(pkgName: String, host: Host) = sys.error("not implemented")
}

case class JettyWebappPackageType() extends PackageType {
  val name = "jetty-webapp"

  lazy val actions = Map(
    "deploy" -> notimpl _,
    "install" -> notimpl _,
    "unblock" -> notimpl _,
    "restart" -> notimpl _,
    "block" -> notimpl _
  )

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