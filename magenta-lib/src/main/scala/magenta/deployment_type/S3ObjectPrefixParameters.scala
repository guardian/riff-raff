package magenta.deployment_type

import magenta.{DeployReporter, DeployTarget, DeploymentPackage}
import magenta.tasks.S3Upload

trait S3ObjectPrefixParameters {
  this: DeploymentType =>

  val prefixStage: Param[Boolean] =
    Param("prefixStage", "Prefix the S3 bucket key with the target stage")
      .default(true)

  val prefixPackage: Param[Boolean] =
    Param("prefixPackage", "Prefix the S3 bucket key with the package name")
      .default(true)

  val prefixStack: Param[Boolean] =
    Param("prefixStack", "Prefix the S3 bucket key with the target stack")
      .default(true)

  val prefixApp: Param[Boolean] = Param[Boolean](
    name = "prefixApp",
    documentation = """
        |Whether to prefix `app` to the S3 location instead of `package`.
        |
        |When `true` `prefixPackage` will be ignored and `app` will be used over `package`, useful if `package` and `app` don't align.
        |""".stripMargin
  ).default(false)

  def getPrefix(
      pkg: DeploymentPackage,
      target: DeployTarget,
      reporter: DeployReporter
  ): String = {
    val maybePackageOrAppName: Option[String] = (
      prefixPackage(pkg, target, reporter),
      prefixApp(pkg, target, reporter)
    ) match {
      case (_, true)      => Some(pkg.app.name)
      case (true, false)  => Some(pkg.name)
      case (false, false) => None
    }

    S3Upload.prefixGenerator(
      stack =
        if (prefixStack(pkg, target, reporter)) Some(target.stack) else None,
      stage =
        if (prefixStage(pkg, target, reporter)) Some(target.parameters.stage)
        else None,
      packageOrAppName = maybePackageOrAppName
    )
  }
}
