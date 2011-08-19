package com.gu.deploy2

trait PackageImpl {
  def actions: Map[String, Host => Seq[Task]]
}

case class JettyWebappPackage() extends PackageImpl {
  lazy val actions = Map(
    "deploy" -> (notimpl _ andThen notimpl _),
    "install" -> notimpl _,
    "unblock" -> notimpl _,
    "restart" -> notimpl _,
    "block" -> notimpl _
  )

  def notimpl(host: Host) = sys.error("not implemented")
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
  def parse(jsonPackage: JsonPackage) = {

    // TODO: obviously the two .get's here are to be replaced with proper error handling
    Package(
      jsonPackage.roles map Role,
      packages.get(jsonPackage.`type`).get
    )
  }

  lazy val packages = Map(
    "jetty-webapp" -> new JettyWebappPackage()
  )
}