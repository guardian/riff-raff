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

  override def perAppActions: AppActionDefinition = {

    case "deploy" => (deployInfo, params) => {
      val host = Host(deployInfo.firstMatchingData("ddm", App("r2"), params.stage.name).
        getOrElse(MessageBroker.fail("no data found for ddm in " + params.stage.name)).value)
      List(
        CopyFile(host as user, zipLocation, "/tmp"),
        ExtractToDocroots(host as user, "/tmp/" + zipInPkg, docrootType, locationInDocroot)
      )
    }
  }
}
object UnzipToDocrootPackageType {
  val name = "unzip-docroot"
}
