package magenta.deployment_type

import magenta.tasks._
import scala.util.Random

object RPM extends DeploymentType {
  val name = "rpm"
  val documentation =
    """
      |Deploys an RPM package to a server.
      |
      |This deploy type deploys an RPM package and runs web or command healthchecks.
      |For each server that is deployed to, the following steps are carried out:
      |
      | - block firewall
      | - copy RPM file to the server
      | - install the RPM using sudo
      | - restart any configured services
      | - if specified, wait for the application port to open
      | - wait until all `healthcheck_paths` return healthy HTTP status
      | - ensure that all healthcheckCommands return exitcode of 0 ???
      | - unblock firewall
    """.stripMargin


  val user = Param("user", "User account on the target hosts to use for executing remote commands").default("riffraff")
  val port = Param[Int]("port", "Application port used for carrying out post deployment healthchecks")
  val healthCheckPaths = Param("healthcheckPaths",
    "List of application paths that must return a healthy HTTP response, appended to `http://<targetHost>:<port>`"
  ).default(List.empty[String])
  val checkseconds = Param("checkseconds",
    "Number of seconds to wait for each healthcheck path to become healthy").default(120)
  val checkUrlReadTimeoutSeconds = Param("checkUrlReadTimeoutSeconds",
    "Read timeout (in seconds) used when checking health check paths").default(5)
  val services = Param("services",
    "Services to restart after RPM installation").defaultFromPackage(pkg => List(pkg.name))
  val serviceCommand = Param("serviceCommand",
    "The command used to restart the service").default("restart")
  val noFileDigest = Param("noFileDigest",
    "Disable per-file digest checking at installation").default(false)

  def randomString(length:Int) = {
    val r = new Random()
    val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLNMOPQRSTUVWXYZ"
    (0 until 10).map(i => chars.charAt(Math.abs(r.nextInt()) % chars.length)).mkString
  }

  override def perHostActions = {
    case "deploy" => pkg => (host, keyRing) => {
      implicit val key = keyRing
      // During preview the pkg.srcDir is not available, so we have to be a bit funky with options
      lazy val rpmFilePath = Option(pkg.srcDir.listFiles()).flatMap(_.headOption).map(_.toString).getOrElse("UnknownDuringPreview")
      lazy val remoteTmpRpm = s"/tmp/riffraff-rpm-${pkg.name}-${randomString(10)}.rpm"

      BlockFirewall(host as user(pkg)) ::
      CopyFile(host as user(pkg), rpmFilePath, remoteTmpRpm) ::
      InstallRpm(host as user(pkg), remoteTmpRpm, noFileDigest(pkg)) ::
      RemoveFile(host as user(pkg), remoteTmpRpm) ::
      services(pkg).map(service => Restart(host as user(pkg), service, serviceCommand(pkg))) :::
      port.get(pkg).toList.map(p => WaitForPort(host, p, 60 * 1000)) :::
      port.get(pkg).toList.map(p => CheckUrls(host, p, healthCheckPaths(pkg), checkseconds(pkg) * 1000, checkUrlReadTimeoutSeconds(pkg))) :::
      UnblockFirewall(host as user(pkg)) ::
      Nil
    }
  }

  def perAppActions = PartialFunction.empty
}