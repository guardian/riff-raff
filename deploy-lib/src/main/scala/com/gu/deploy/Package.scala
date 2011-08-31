package com.gu.deploy

import java.io.File

case class Package(
  name: String,
  pkgRoles: Set[Role],
  pkgSpecificData: Map[String, String],
  pkgTypeName: String,
  srcDir: File) {

  def mkAction(name: String): Action = pkgType.mkAction(name)

  lazy val pkgType = pkgTypeName match {
    case "jetty-webapp" => new JettyWebappPackageType(this)
    case "resin-webapp" => new ResinWebappPackageType(this)
    case "file" => new FilePackageType(this)
    case "demo" => new DemoPackageType(this)
    case unknown => sys.error("Package type %s of package %s is unknown" format (unknown, name))
  }

  val data = pkgType.defaultData ++ pkgSpecificData

  val roles = pkgRoles
}