package magenta.deployment_type

import magenta.tasks._
import magenta.tasks.CheckUrls
import magenta.tasks.Service
import magenta.tasks.BlockFirewall
import scala.Some
import magenta.tasks.WaitForPort

object NativePackagerWebapp extends WebApp {
  val containerName = "native"
  def webAppDocumentation: String = ""
  override def documentation =
    s"""
       |Deploys a tarball created by sbt-native-packager onto a target host.
       |
       |This deploy type deploys a tar.gz tarball output from the universal:packageTarball of
       |the sbt-native-packager plugin (similar to Play's native dist command.
       |
       |Note that deployment type assumes that you do not include the version in the artifact name
       |or top level path in the tarball. You can achieve this by using the following snippet:
       |```
       |name in Universal := name.value
       |```
       |
       |The following steps are carried out
       |
       | - block firewall
       | - copy tarball over to the remote host
       | - delete the existing directory on the remote host
       | - expand the tarball on the remote host
       | - restart the service
       | - wait for the application port to open
       | - wait until all `healthcheck_paths` return healthy HTTP status
       | - unblock firewall
     """.stripMargin

  override lazy val defaultUser = Some("jvmuser")

  val applicationName = Param[String]("applicationName",
    "The application name (this is the name given to the tarball by sbt and also the top level directory inside the tarball)"
  ).defaultFromPackage{ pkg => servicename(pkg) }
  val tarball = Param[String]("tarball",
    "The tarball filename and path within the package directory of the artifacts.zip"
  ).defaultFromPackage{ pkg => s"${applicationName(pkg)}.tar.gz" }

  override def perHostActions = {
    case "deploy" => pkg => (logger, host, keyRing) => {
      implicit val key = keyRing
      val applicationRoot = s"/$containerName-apps/${servicename(pkg)}"
      val remoteTarballLocation = s"$applicationRoot/readyToDeploy.tar.gz"

      BlockFirewall(host as user(pkg)) ::
      CopyFile(host as user(pkg), s"${pkg.srcDir.getPath}/${tarball(pkg)}", remoteTarballLocation) ::
      Service(host as user(pkg), servicename(pkg), "stop") ::
      RemoveFile(host as user(pkg), s"$applicationRoot/${applicationName(pkg)}", recursive=true) ::
      DecompressTarBall(host as user(pkg), remoteTarballLocation, applicationRoot) ::
      RemoveFile(host as user(pkg), remoteTarballLocation) ::
      Service(host as user(pkg), servicename(pkg), "start") ::
      WaitForPort(host, port(pkg), waitseconds(pkg) * 1000) ::
      CheckUrls(host, port(pkg), healthcheck_paths(pkg), checkseconds(pkg) * 1000, checkUrlReadTimeoutSeconds(pkg)) ::
      UnblockFirewall(host as user(pkg)) ::
      Nil
    }
  }

}
