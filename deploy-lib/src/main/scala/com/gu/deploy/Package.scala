package com.gu.deploy

case class Package(pkgName: String, pkgRoles: Set[Role], pkgTypeName: String) {

  def mkAction(name: String): Action = pkgType.mkAction(name) getOrElse {
    sys.error("Action %s is not supported on package %s of type %s" format (name, pkgName, pkgType.name))
  }

  lazy val pkgType = pkgTypeName match {
    case "jetty-webapp" => new JettyWebappPackageType(this)
    case "file" => new FilePackageType(this)
    case unknown => sys.error("Package type %s of package %s is unknown" format (unknown, pkgName))
  }

  val roles = pkgRoles
}