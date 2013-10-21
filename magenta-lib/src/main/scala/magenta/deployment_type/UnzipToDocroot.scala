package magenta.deployment_type

import magenta.{MessageBroker, App, Host}
import java.io.File
import magenta.tasks.{ExtractToDocroots, CopyFile}


object UnzipToDocroot  extends DeploymentType {
  val name = "unzip-docroot"
  val documentation =
    """
      |Unzip package files to the specified DDM docroot.
      |
      |This deployment type consists of two steps:
      |
      | - copy the specified ZIP file to `/tmp/` on the correct DDM host (looked up in deploy info)
      | - execute a script on the DDM host to deploy the files contained in the ZIP file to a specified docroot
    """.stripMargin

  val user = Param[String]("user")
  val zip = Param[String]("zip")
  val docrootType = Param[String]("docrootType")
  val locationInDocroot = Param("locationInDocroot").default("")

  def perAppActions = {
    case "deploy" => pkg => (deployInfo, params) => {
      lazy val zipLocation = new File(pkg.srcDir, zip(pkg))
      val host = Host(deployInfo.firstMatchingData("ddm", App("r2"), params.stage.name).
        getOrElse(MessageBroker.fail("no data found for ddm in " + params.stage.name)).value)
      List(
        CopyFile(host as user(pkg), zipLocation.getPath, "/tmp"),
        ExtractToDocroots(host as user(pkg), "/tmp/" + zipLocation.getName, docrootType(pkg), locationInDocroot(pkg))
      )
    }
  }
}