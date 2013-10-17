package magenta

import java.io.File
import net.liftweb.json.JsonAST._
import magenta.packages.PackageType

case class Package(
  name: String,
  pkgApps: Set[App],
  pkgSpecificData: Map[String, JValue],
  pkgTypeName: String,
  srcDir: File) {

  def mkAction(name: String): Action = pkgType.mkAction(name)(this)

  lazy val pkgType = PackageType.all find (_.name == pkgTypeName) getOrElse (
    throw new IllegalArgumentException(s"Package type $pkgTypeName of package $name is unknown")
  )

  val apps = pkgApps
}