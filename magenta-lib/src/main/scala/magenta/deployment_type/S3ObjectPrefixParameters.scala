package magenta.deployment_type

import magenta.{DeployReporter, DeployTarget, DeploymentPackage}
import magenta.tasks.S3Upload

trait S3ObjectPrefixParameters {
  this: DeploymentType =>

  val prefixStage: Param[Boolean] =
    Param[Boolean](
      "prefixStage",
      "Prefix the S3 bucket key with the target stage"
    )
      .default(true)

  val prefixPackage: Param[Boolean] =
    Param[Boolean](
      "prefixPackage",
      "Prefix the S3 bucket key with the package name"
    )
      .default(true)

  val prefixStack: Param[Boolean] =
    Param[Boolean](
      "prefixStack",
      "Prefix the S3 bucket key with the target stack"
    )
      .default(true)

  val prefixApp: Param[Boolean] = Param[Boolean](
    name = "prefixApp",
    documentation = """
        |Whether to prefix `app` to the S3 location instead of `package`.
        |
        |When `true` `prefixPackage` will be ignored and `app` will be used over `package`, useful if `package` and `app` don't align.
        |""".stripMargin
  ).default(false)

  val prefixStagePaths: Param[Map[String, String]] = Param[Map[String, String]](
    "prefixStagePaths",
    documentation = """
        |This option allows full control over the paths used for each stage, and will override all other prefix options.
        |
        |This is a useful escape hatch if you find yourself in a scenario where you are deploying to a single bucket and
        |the upload path between stages cannot be repeated.
        |
        |**Note:** For the most part, you should not use this.
        |If at all possible, prefer to use a single bucket per-stage.
        |
        |See our recommendation here: https://github.com/guardian/recommendations/blob/main/AWS.md?plain=1#L70
        |```yaml
        |prefixStagePaths:
        |  CODE: atoms-CODE
        |  PROD: atoms
        |```
        |""".stripMargin
  ).default(Map.empty)

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

    val prefixFromStagePaths: Map[String, String] =
      prefixStagePaths(pkg, target, reporter)

    if (prefixFromStagePaths.isEmpty) {
      S3Upload.prefixGenerator(
        stack =
          if (prefixStack(pkg, target, reporter)) Some(target.stack)
          else None,
        stage =
          if (prefixStage(pkg, target, reporter))
            Some(target.parameters.stage)
          else None,
        packageOrAppName = maybePackageOrAppName
      )
    } else {
      prefixFromStagePaths.get(target.parameters.stage.name) match {
        case Some(prefix) => prefix
        case _ =>
          reporter.fail(
            s"""
               |Unable to locate prefix for stage ${target.parameters.stage.name}.
               |
               |prefixStagePaths is set to:
               |
               |$prefixFromStagePaths
               |
               |To resolve, either:
               |  - Deploy to a known stage
               |  - Update prefixStagePaths, adding a value for ${target.parameters.stage.name}
               |""".stripMargin
          )
      }
    }
  }
}
