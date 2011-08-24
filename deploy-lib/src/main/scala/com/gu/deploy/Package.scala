package com.gu.deploy

import tasks.Task

case class Package(pkgName: String, pkgRoles: Set[Role], pkgType: PackageType) {

  class PackageAction(f: (String,Host) => List[Task], actionName: String) extends Action {
    def resolve(host: Host) = f(pkgName, host)
    def roles = pkgRoles
    def description = pkgName + "." + actionName

    override def toString = "action " + description
  }

  def mkAction(name: String): Action = {
    val actionFunc = pkgType.actions.get(name).getOrElse {
      sys.error("Action %s is not supported on package %s of type %s" format (name, pkgName, pkgType.name))
    }
    new PackageAction(actionFunc, name)
  }

  val roles = pkgRoles
}