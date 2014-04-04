package magenta.deployment_type

import magenta.tasks._
import java.io.File
import magenta.{KeyRing, Host, DeploymentPackage}

trait WebApp extends DeploymentType with S3AclParams {
  def containerName: String
  def webAppDocumentation: String
  def documentation: String =
    webAppDocumentation +
      """
        |
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

  val user = Param("user",
    "User account on the target hosts to use for executing remote commands"
  ).default(defaultUser.getOrElse(containerName))
  val port = Param("port", "Application port used for carrying out post deployment healthchecks").default(8080)
  val servicename = Param("servicename",
    """
      |The name of the **service** to restart on the target hosts, typically restarted using
      |`sudo service <servicename> restart`
      |""".stripMargin
  ).defaultFromPackage(_.name)
  val bucket = Param[String]("bucket",
    "When the `uploadArtifacts` action is used, this specifies the target S3 bucket")
  val waitseconds = Param("waitseconds",
    "Number of seconds to wait for the application port to start accepting connections after restart").default(60)
  val checkseconds = Param("checkseconds",
    "Number of seconds to wait for each healthcheck path to become healthy").default(120)
  val healthcheck_paths = Param("healthcheck_paths",
    "List of application paths that must return a healthy HTTP response, appended to `http://<targetHost>:<port>`"
  ).defaultFromPackage{ pkg => List(s"/${servicename(pkg)}/management/healthcheck") }
  val checkUrlReadTimeoutSeconds = Param("checkUrlReadTimeoutSeconds",
    "Read timeout (in seconds) used when checking health check paths").default(5)
  val copyRoots = Param("copyRoots",
    """
      |Specify a list of directory roots that should be copied over to the target hosts. This defaults to a single
      |root of `""` which in effect uses the root of the package directory. This is designed to be used in conjunction
      |with `copyMode=mirror` in order to isolate which directories on the target host can have files removed during
      |the copy task.
      |
      |As an example, you might specify copy roots of `webapps` and `solr/conf` and copy mode of mirror. In this case
      |files under both these directories will be mirrored from the matching directories in the package. However, if
      |copy roots were not explicitly specified then any files in the parent directory or in `solr` that were not in
      |the package would also be removed.
    """.stripMargin
  ).default(List(""))
  val copyMode = Param("copyMode",
    """
      |Controls the mode of the underlying rsync command that is used to copy files to the target hosts.
      |
      | - `additive`: Copies files from the `copyRoots` to the target host path, files with the same name on the target
      | will be overwritten but otherwise the copy will be non-destructive. Equivalent to rsync flags of `-rpv`.
      | - `mirror`: Copies files from the `copyRoots` to the target host path, each root will be mirrored to the target
      | host, overwriting existing files and removing any files that are on the target host but not in the copy root.
      | Equivalent of using rsync with the flags `-rpv --delete --delete-after`.
      |
      |This is useful when configuration or data files must be removed. Use in conjunction with `copyRoots` to control
      |more precisely which directories on the target host are affected.
    """.stripMargin
  ).default("additive")

  override def perHostActions = {
    case "deploy" => pkg => (host, keyRing) => {
      implicit val key = keyRing

      BlockFirewall(host as user(pkg)) ::
      rootCopies(pkg, host) :::
      Restart(host as user(pkg), servicename(pkg)) ::
      WaitForPort(host, port(pkg), waitseconds(pkg) * 1000) ::
      CheckUrls(host, port(pkg), healthcheck_paths(pkg), checkseconds(pkg) * 1000, checkUrlReadTimeoutSeconds(pkg)) ::
      UnblockFirewall(host as user(pkg)) ::
      Nil
    }
    case "restart" => pkg => (host, keyRing) => {
      implicit val key = keyRing
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
    case "uploadArtifacts" => pkg => (lookup, parameters, stack) =>
      implicit val keyRing = lookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      List(
        S3Upload(stack, parameters.stage, bucket(pkg), new File(pkg.srcDir.getPath), publicReadAcl = publicReadAcl(pkg))
      )
  }

  def rootCopies(pkg: DeploymentPackage, host: Host)(implicit keyRing: KeyRing) = {
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
  val webAppDocumentation: String =
    s"""
      |The $name deployment type extends the WebApp trait in order to deploy an executable jar
      |onto a target host.
    """.stripMargin

  override lazy val defaultUser = Some("jvmuser")
  lazy val containerName = "executable-jar"
}

object JettyWebapp extends WebApp {
  val webAppDocumentation: String =
    s"""
      |The $name deployment type extends the WebApp trait in order to deploy an application into a Jetty container.
    """.stripMargin

  lazy val containerName = "jetty"
}

object ResinWebapp extends WebApp {
  val webAppDocumentation =
    s"""
      |The $name deployment type extends the WebApp trait in order to deploy an application into a Resin container.
    """.stripMargin

  lazy val containerName = "resin"
}