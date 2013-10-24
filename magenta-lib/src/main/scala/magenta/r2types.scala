package magenta

import magenta.tasks.{ExtractToDocroots, CopyFile}
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json.Implicits._
import java.io.File

case class UnzipToDocrootPackageType(pkg: Package) extends PackageType {
  def name = UnzipToDocrootPackageType.name

  override def defaultData = Map[String, JValue](
    "locationInDocroot" -> ""
  )

  lazy val user = pkg.data.string("user")
  lazy val zipInPkg = pkg.data.string("zip")
  lazy val zipLocation = new File(pkg.srcDir, zipInPkg)
  lazy val docrootType = pkg.data.string("docrootType")
  lazy val locationInDocroot = pkg.data.string("locationInDocroot")

  override def perAppActions: AppActionDefinition = {

    case "deploy" => (deployInfo, params) => {
      val host = Host(deployInfo.firstMatchingData("ddm", App("r2"), params.stage.name).
        getOrElse(MessageBroker.fail("no data found for ddm in " + params.stage.name)).value)
      List(
        CopyFile(host as user, zipLocation.getPath, "/tmp"),
        ExtractToDocroots(host as user, "/tmp/" + zipLocation.getName, docrootType, locationInDocroot)
      )
    }
  }
}
object UnzipToDocrootPackageType {
  val name = "unzip-docroot"
}
