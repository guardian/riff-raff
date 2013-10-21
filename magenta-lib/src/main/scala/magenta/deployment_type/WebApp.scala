package magenta.deployment_type

import magenta.tasks._
import java.io.File
import magenta.{Host, Package}

trait WebApp extends DeploymentType {
  def containerName: String
  def documentation: String =
    """
      |The WebApp trait deploys JVM based web applications that are deployed by copying over a new artifact (a WAR or
      |executable JAR) and restarting a container.
      |
      |The deploy type does the following for each host that is being deployed to:
      |
      | - block the firewall
      | - copy the new artifact over to the target host
      | - execute the container's restart command
      | - wait for the container's port to open again
      | - wait for all `heathcheck_paths` to return a healthy HTTP status code
      | - unblock the firewall
      |
      |Two actions are provided: `deploy` and `restart`. These are identical except that in the latter case the actual
      |copying of the artifact to the target host is not carried out, resulting in a rolling restart of all hosts.
    """.stripMargin

  lazy val name = containerName + "-webapp"
  lazy val defaultUser: Option[String] = None

  val user = Param("user").default(defaultUser.getOrElse(containerName))
  val port = Param("port").default(8080)
  val servicename = Param("servicename").defaultFromPackage(_.name)
  val bucket = Param[String]("bucket")
  val waitseconds = Param("waitseconds").default(60)
  val checkseconds = Param("checkseconds").default(120)
  val healthcheck_paths = Param("healthcheck_paths").defaultFromPackage{ pkg =>
    List(s"/${servicename(pkg)}/management/healthcheck")
  }
  val checkUrlReadTimeoutSeconds = Param("checkUrlReadTimeoutSeconds").default(5)
  val copyRoots = Param("copyRoots").default(List(""))
  val copyMode = Param("copyMode").default("additive")

  override def perHostActions = {
    case "deploy" => pkg => host => {
      BlockFirewall(host as user(pkg)) ::
      rootCopies(pkg, host) :::
      Restart(host as user(pkg), servicename(pkg)) ::
      WaitForPort(host, port(pkg), waitseconds(pkg) * 1000) ::
      CheckUrls(host, port(pkg), healthcheck_paths(pkg), checkseconds(pkg) * 1000, checkUrlReadTimeoutSeconds(pkg)) ::
      UnblockFirewall(host as user(pkg)) ::
      Nil
    }
    case "restart" => pkg => host => {
      List(
        BlockFirewall(host as user(pkg)),
        Restart(host as user(pkg), servicename(pkg)),
        WaitForPort(host, port(pkg), waitseconds(pkg) * 1000),
        CheckUrls(host, port(pkg), healthcheck_paths(pkg), checkseconds(pkg) * 1000, checkUrlReadTimeoutSeconds(pkg)),
        UnblockFirewall(host as user(pkg))
      )
    }
  }

  def perAppActions = {
    case "uploadArtifacts" => pkg => (_, parameters) =>
      List(
        S3Upload(parameters.stage, bucket(pkg), new File(pkg.srcDir.getPath))
      )
  }

  def rootCopies(pkg: Package, host: Host) = {
    val TRAILING_SLASH = """^(.*/)$""".r
    copyRoots(pkg).map{ root =>
      val rootWithTrailingSlash = root match {
        case "" => root
        case TRAILING_SLASH(withSlash) => withSlash
        case noTrailingSlash => s"$noTrailingSlash/"
      }
      CopyFile(host as user(pkg), s"${pkg.srcDir.getPath}/$rootWithTrailingSlash",
        s"/$containerName-apps/${servicename(pkg)}/$rootWithTrailingSlash", copyMode(pkg))
    }
  }
}

object ExecutableJarWebapp extends WebApp {
  override lazy val defaultUser = Some("jvmuser")
  lazy val containerName = "executable-jar"
}

object JettyWebapp extends WebApp {
  lazy val containerName = "jetty"
}

object ResinWebapp extends WebApp {
  lazy val containerName = "resin"
}