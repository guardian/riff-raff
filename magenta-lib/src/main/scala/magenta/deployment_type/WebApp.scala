package magenta.deployment_type

import net.liftweb.json.JsonAST.{JArray, JValue}
import magenta.tasks._
import java.io.File
import magenta.{Host, Package}

trait WebApp extends DeploymentType {
  def containerName: String

  lazy val name = containerName + "-webapp"
  lazy val defaultUser: Option[String] = None

  val params = Seq(user, port, servicename, bucket, waitseconds, checkseconds, healthcheck_paths,
    checkUrlReadTimeoutSeconds, copyRoots, copyMode)

  val user = Param("user", Some(defaultUser.getOrElse(containerName)))
  val port = Param("port", Some(8080))
  val servicename = Param("servicename", defaultFromPackage = pkg => Some(pkg.name))
  val bucket = Param[String]("bucket")
  val waitseconds = Param("waitseconds", Some(60))
  val checkseconds = Param("checkseconds", Some(120))
  val healthcheck_paths = Param("healthcheck_paths", defaultFromPackage = pkg =>
    Some(List(s"/${servicename(pkg)}/management/healthcheck"))
  )
  val checkUrlReadTimeoutSeconds = Param("checkUrlReadTimeoutSeconds", Some(5))
  val copyRoots = Param("copyRoots", Some(List("")))
  val copyMode = Param("copyMode", Some("additive"))

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