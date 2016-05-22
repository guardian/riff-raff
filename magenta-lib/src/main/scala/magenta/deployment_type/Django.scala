package magenta.deployment_type

import magenta.tasks._

object Django extends DeploymentType {
  val name = "django-webapp"
  val documentation =
    """
      |Deploys a Guardian django web application to a set of servers.
      |
      |This deploy type deploys a Django based web application. The target servers are assumed to be configured
      |using the usual Guardian configuration for a Django WSGI application running under Apache httpd. For each server
      |that is running the application the following steps are carried out:
      |
      | - block firewall
      | - copy package files to a versioned directory under `/django-apps/`
      | - create a new symlink to make the new versioned directory the new default
      | - gracefully restart httpd
      | - wait for the application port to open
      | - wait until all `healthcheck_paths` return healthy HTTP status
      | - unblock firewall
    """.stripMargin

  val user = Param("user", "User account on the target hosts to use for executing remote commands").default("django")
  val port = Param("port", "Application port used for carrying out post deployment healthchecks").default(80)
  val healthCheckPaths = Param("healthcheck_paths",
    "List of application paths that must return a healthy HTTP response, appended to `http://<targetHost>:<port>`"
  ).default(List.empty[String])
  val checkseconds = Param("checkseconds",
    "Number of seconds to wait for each healthcheck path to become healthy").default(120)
  val checkUrlReadTimeoutSeconds = Param("checkUrlReadTimeoutSeconds",
    "Read timeout (in seconds) used when checking health check paths").default(5)
  val deploysToKeep = Param("deploysToKeep", "The number of deploys to keep on the server").default(0)
  val deployArtifactPrefix = Param("deployArtifactPrefix",
    "The prefix of the deploy artifact, used to find directories to cleanup").defaultFromPackage( _.name )

  override def perHostActions = {
    case "deploy" => pkg => (logger, host, keyRing) => {
      implicit val key = keyRing
      val destDir = "/django-apps/"
      // During preview the pkg.srcDir is not available, so we have to be a bit funky with options
      lazy val appVersionPath = Option(pkg.srcDir.listFiles()).flatMap(_.headOption)
      List(
        BlockFirewall(host as user(pkg)),
        CompressedCopy(host as user(pkg), appVersionPath, destDir),
        Link(host as user(pkg), appVersionPath.map(destDir + _.getName), "/django-apps/%s" format pkg.name),
        ApacheGracefulRestart(host as user(pkg)),
        CleanupOldDeploys(host as user(pkg), deploysToKeep(pkg), destDir, deployArtifactPrefix(pkg)),
        WaitForPort(host, port(pkg), 60 * 1000),
        CheckUrls(host, port(pkg), healthCheckPaths(pkg), checkseconds(pkg) * 1000, checkUrlReadTimeoutSeconds(pkg)),
        UnblockFirewall(host as user(pkg))
      )
    }
  }

  def perAppActions = PartialFunction.empty
}