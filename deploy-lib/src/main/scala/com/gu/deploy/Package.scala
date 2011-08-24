package com.gu.deploy

import tasks.Task

case class Package(pkgName: String, pkgRoles: Set[Role], pkgType: PackageType) {

  def mkAction(name: String): Action = pkgType.mkAction(this, name) getOrElse {
    sys.error("Action %s is not supported on package %s of type %s" format (name, pkgName, pkgType.name))
  }

  val roles = pkgRoles
}