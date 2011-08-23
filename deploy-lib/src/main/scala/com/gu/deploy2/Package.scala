package com.gu.deploy2

case class Package(pkgName: String, pkgRoles: Set[Role], pkgType: PackageType) {

  class PackageAction(f: (String,Host) => List[Task], actionName: String) extends Action {
    def resolve(host: Host) = f(pkgName, host)
    def roles = pkgRoles
    def description = pkgName + "." + actionName

    override def toString = "action " + description
  }

  def mkAction(name: String): Action = {
    val actionFunc = pkgType.actions.get(name).getOrElse(sys.error("Unknown action: " + name))
    new PackageAction(actionFunc, name)
  }

  val roles = pkgRoles
}