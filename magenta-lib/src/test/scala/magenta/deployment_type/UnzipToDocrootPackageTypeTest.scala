package magenta
package deployment_type

import fixtures._
import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json.Implicits._
import java.io.File
import tasks._
import tasks.CopyFile
import magenta.DeploymentPackage

class UnzipToDocrootTest extends FunSuite with ShouldMatchers{
  test("copies to host based on deployInfo data") {
    val packageData: Map[String, JValue] = Map(
      "user" -> "ddm-user",
      "zip" -> "dir/files.zip",
      "docrootType" -> "static",
      "locationInDocroot" -> "the-app/static"
    )

    val p = DeploymentPackage("app", Seq.empty, packageData, UnzipToDocroot.name, new File("/tmp/packages/webapp"))

    val diData = Map("ddm" -> List(Datum(None, "*", PROD.name, "ddm.domain", None)))

    UnzipToDocroot.perAppActions("deploy")(p)(stubLookup(resourceData = diData), parameters()) should be (List(
      CopyFile(Host("ddm.domain", connectAs = Some("ddm-user")), "/tmp/packages/webapp/dir/files.zip", "/tmp"),
      ExtractToDocroots(Host("ddm.domain", connectAs = Some("ddm-user")), "/tmp/files.zip", "static", "the-app/static")
    ))
  }

}
