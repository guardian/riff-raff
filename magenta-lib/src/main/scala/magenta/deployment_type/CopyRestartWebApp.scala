package magenta.deployment_type

import magenta.tasks._

object CopyRestartWebApp extends DeploymentType {
  val name = "copy-restart-webapp"
  val documentation =
    """
      |Deploys a generic webapp based on copying some files and restarting one or more services.
      |
      |This deploy type deploys a web application. This is fairly generic and is designed to
      |allow deployment to be configured.
      |
      | - block firewall
      | - copy package files to the server
      | - optionally create a new symlink if the target directory is versioned
      | - restart services
      | - wait for the application port to open
      | - wait until all `healthcheck_paths` return healthy HTTP status
      | - unblock firewall
    """.stripMargin

  val user = Param("user", "User account on the target hosts to use for executing remote commands")
    .defaultFromPackage(_.name)
  val port = Param("port", "Application port used for carrying out post deployment healthchecks").default(80)
  val healthCheckPaths = Param("healthcheck_paths",
    "List of application paths that must return a healthy HTTP response, appended to `http://<targetHost>:<port>`"
  ).default(List("/"))
  val checkseconds = Param("checkseconds",
    "Number of seconds to wait for each healthcheck path to become healthy").default(120)
  val checkUrlReadTimeoutSeconds = Param("checkUrlReadTimeoutSeconds",
    "Read timeout (in seconds) used when checking health check paths").default(5)
  val applicationRoot = Param("applicationRoot",
    "The directory to which the contents of the package should be copied")
    .defaultFromPackage(pkg => s"/opt/${pkg.name}")
  val linkCreation = Param("linkCreation", "Create a symlink if the package content is versioned").default(true)
  val linkName = Param("linkName",
    "The link name (in applicationRoot) for creating a symlink if the package content is versioned")
    .defaultFromPackage(pkg => s"${pkg.name}")
  val services = Param("services", "List of services to restart")
    .defaultFromPackage(pkg => List(pkg.name))

  override def perHostActions = {
    case "deploy" => pkg => (host, keyRing) => {
      implicit val key = keyRing
      val destDir = applicationRoot(pkg)
      // During preview the pkg.srcDir is not available, so we have to be a bit funky with options
      val appVersionPath = Option(pkg.srcDir.listFiles()).flatMap(_.headOption)

      BlockFirewall(host as user(pkg)) ::
      CompressedCopy(host as user(pkg), appVersionPath, destDir) ::
      { if (linkCreation(pkg)) List(Link(host as user(pkg), appVersionPath.map(destDir + _.getName), linkName(pkg))) else Nil } :::
      services(pkg).map(service => Restart(host as user(pkg), service)) :::
      WaitForPort(host, port(pkg), 60 * 1000) ::
      CheckUrls(host, port(pkg), healthCheckPaths(pkg), checkseconds(pkg) * 1000, checkUrlReadTimeoutSeconds(pkg)) ::
      UnblockFirewall(host as user(pkg)) ::
      Nil
    }
  }

  def perAppActions = PartialFunction.empty
}