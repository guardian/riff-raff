package com.gu.deploy2

trait PackageType {
  def actions: Map[String, Host => Seq[Task]]

  def notimpl(host: Host) = sys.error("not implemented")
}

case class JettyWebappPackageType() extends PackageType {
  lazy val actions = Map(
    "deploy" -> (notimpl _ andThen notimpl _),
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

case class Package(pkgName: String, pkgRoles: List[Role], pkgType: PackageType) {

  class PackageAction(f: Host => Seq[Task], actionName: String) extends Action {
    def resolve(host: Host) = f(host)
    def roles = pkgRoles
    def description = pkgName + "." + actionName

    override def toString = "action " + description
  }

  def mkAction(name: String): Action = {
    val actionFunc = pkgType.actions.get(name).getOrElse(sys.error("Unknown action: " + name))
    new PackageAction(actionFunc, name)
  }
}

object Package {
  lazy val packageTypes = Map(
    "jetty-webapp" -> new JettyWebappPackageType(),
    "file" -> new FilePackageType()
  )
}