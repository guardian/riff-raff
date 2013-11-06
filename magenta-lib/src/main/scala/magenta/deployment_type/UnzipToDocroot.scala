package magenta.deployment_type

import magenta.{MessageBroker, App, Host}
import java.io.File
import magenta.tasks.{ExtractToDocroots, CopyFileTask}


object UnzipToDocroot  extends DeploymentType {
  val name = "unzip-docroot"
  val documentation =
    """
      |Unzip package files to the specified DDM docroot. The DDM (Distributed Docroot Manager) is a legacy system
      |used to ensure that files are copied to each data centre.
      |
      |This deployment type consists of two steps:
      |
      | - copy the specified ZIP file to `/tmp/` on the correct DDM host (looked up in deploy info)
      | - execute a script (`/opt/bin/deploy_static_files.sh`) on the DDM host to deploy the files contained in the ZIP
      | file to a specified docroot
    """.stripMargin

  val user = Param[String]("user", "User on the DDM host used to copy the ZIP file and execute the remote script")
  val zip = Param[String]("zip", "The name of the ZIP file").defaultFromPackage(pkg => s"${pkg.name}.zip")
  val docrootType = Param[String]("docrootType",
    "The name of the docroot to lookup in the target host's DDM configuration file")
  val locationInDocroot = Param("locationInDocroot",
    """The location in the DDM docroot to deploy the static files into - this is essentially a file path prefix that
      |is added to all of the files that are deployed to the docroot.
    """.stripMargin
  ).default("")

  def perAppActions = {
    case "deploy" => pkg => (resourceLookup, params) => {
      lazy val zipLocation = new File(pkg.srcDir, zip(pkg))
      val host = Host(resourceLookup.data.datum("ddm", App("r2"), params.stage).
        getOrElse(MessageBroker.fail("no data found for ddm in " + params.stage.name)).value)
      List(
        CopyFileTask(host as user(pkg), zipLocation.getPath, "/tmp"),
        ExtractToDocroots(host as user(pkg), "/tmp/" + zipLocation.getName, docrootType(pkg), locationInDocroot(pkg))
      )
    }
  }
}