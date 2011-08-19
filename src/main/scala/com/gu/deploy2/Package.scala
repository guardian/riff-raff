package com.gu.deploy2

trait PackageImpl {
  def actions: Map[String, Host => Seq[Task]]
  def notimpl(host: Host) = sys.error("not implemented")

}

case class JettyWebappPackage() extends PackageImpl {
  lazy val actions = Map(
    "deploy" -> (notimpl _ andThen notimpl _),
    "install" -> notimpl _,
    "unblock" -> notimpl _,
    "restart" -> notimpl _,
    "block" -> notimpl _
  )

}
case class FilePackage() extends PackageImpl {

  lazy val actions = Map(
    "deploy" -> copyFiles _
  )

  def copyFiles(host: Host) = {
    List(CopyFileTask("packages/%s/*" format ("appname"), "/"))
  }
}

case class Package(roles: List[Role], impl: PackageImpl) {

  class PackageSuppliedAction(f: Host => Seq[Task], val name: String) extends Action {
    def resolve(host: Host) = f(host)

    // hmm can't find out the package name!
    override def toString = "action " + name
  }

  def action(name: String): Action = {
    val actionFunc = impl.actions.get(name).getOrElse(sys.error("Unknown action: " + name))
    new PackageSuppliedAction(actionFunc, name)
  }
}

object Package {
  lazy val packages = Map(
    "jetty-webapp" -> new JettyWebappPackage(),
    "file" -> new FilePackage()
  )
}