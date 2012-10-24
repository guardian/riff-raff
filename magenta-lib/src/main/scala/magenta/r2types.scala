package magenta

import magenta.tasks.{ExtractToDocroots, CopyFile}
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json.Implicits._

case class UnzipToDocrootPackageType(pkg: Package) extends PackageType {
  def name = UnzipToDocrootPackageType.name

  override def defaultData = Map[String, JValue](
    "locationInDocroot" -> ""
  )

  lazy val user = pkg.stringData("user")
  lazy val zipInPkg = pkg.stringData("zip")
  lazy val zipLocation = pkg.srcDir.getPath  + "/" + zipInPkg
  lazy val docrootType = pkg.stringData("docrootType")
  lazy val locationInDocroot = pkg.stringData("locationInDocroot")

  override def perHostActions: HostActionDefinition = {

    case "deploy" => host => {
      List(
        CopyFile(host as user, zipLocation, "/tmp"),
        ExtractToDocroots(host, docrootType, locationInDocroot)
      )
    }
  }
}
object UnzipToDocrootPackageType {
  val name = "unzip-docroot"
}
