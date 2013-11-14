package magenta.deployment_type

import magenta.tasks._
import java.io.File

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
  val portWaitSeconds = Param("portWaitSeconds",
    "Maximum number of seconds to wait for the application port to become available").default(60)
  val healthCheckPaths = Param("healthcheck_paths",
    "List of application paths that must return a healthy HTTP response, appended to `http://<targetHost>:<port>`"
  ).default(List.empty[String])
  val checkseconds = Param("checkseconds",
    "Number of seconds to wait for each healthcheck path to become healthy").default(120)
  val checkUrlReadTimeoutSeconds = Param("checkUrlReadTimeoutSeconds",
    "Read timeout (in seconds) used when checking health check paths").default(5)

  override def perHostActions = {
    case "deploy" => pkg => host => {
      val destDir = "/django-apps/"

      List(
        BlockFirewall(user)(pkg, host),
        CopyChildDirectory(user, destDir)(pkg, host),
        LinkChildDirectory(user, destDir)(pkg, host),
        ApacheGracefulRestart(user)(pkg, host),
        WaitForPort(port, portWaitSeconds)(pkg, host),
        CheckUrls(port, healthCheckPaths, checkseconds, checkUrlReadTimeoutSeconds)(pkg, host),
        UnblockFirewall(user)(pkg, host)
      )
    }
  }

  def perAppActions = PartialFunction.empty
}