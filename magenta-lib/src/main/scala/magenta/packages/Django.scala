package magenta.packages

import magenta.tasks._

object Django extends PackageType {
  val name = "django-webapp"

  val params = Seq(user, port, healthCheckPaths, checkseconds, checkUrlReadTimeoutSeconds)

  val user = Param("user", Some("django"))
  val port = Param("port", Some(80))
  val healthCheckPaths = Param("healthcheck_paths", Some(List.empty[String]))
  val checkseconds = Param("checkseconds", Some(120))
  val checkUrlReadTimeoutSeconds = Param("checkUrlReadTimeoutSeconds", Some(5))

  override def perHostActions = {
    case "deploy" => pkg => host => {
      val destDir = "/django-apps/"
      // During preview the pkg.srcDir is not available, so we have to be a bit funky with options
      lazy val appVersionPath = Option(pkg.srcDir.listFiles()).flatMap(_.headOption)
      List(
        BlockFirewall(host as user(pkg)),
        CompressedCopy(host as user(pkg), appVersionPath, destDir),
        Link(host as user(pkg), appVersionPath.map(destDir + _.getName), "/django-apps/%s" format pkg.name),
        ApacheGracefulRestart(host as user(pkg)),
        WaitForPort(host, port(pkg), 60 * 1000),
        CheckUrls(host, port(pkg), healthCheckPaths(pkg), checkseconds(pkg) * 1000, checkUrlReadTimeoutSeconds(pkg)),
        UnblockFirewall(host as user(pkg))
      )
    }
  }

  def perAppActions = PartialFunction.empty
}