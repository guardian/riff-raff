package magenta.deployment_type

import java.io.File

import magenta.tasks.{S3Upload, UpdateS3Lambda}

object S3Lambda extends DeploymentType  {
  val name = "aws-s3-lambda"
  val documentation =
    """
      |Provides two deploy actions:
      | - `uploadLambda` - uploads a new lambda file to S3
      | - `updateLambda` - makes a Lambda Update Function Code request to refresh after uploading a new file to S3
    """.stripMargin

  val bucket = Param[String]("bucket", "Name of the S3 bucket where the lambda archive should be uploaded")

  val functionName = Param[String]("functionName", "The stub of the function name (will be suffixed with the stage, e.g. MyFunction- becomes MyFunction-CODE)")

  val fileName = Param[String]("fileName", "The name of the archive of the function")


  def perAppActions = {
    case "uploadLambda" => (pkg) => (resourceLookup, parameters, stack) => {
      List(
        S3Upload(
          stack,
          parameters.stage,
          bucket(pkg),
          new File(s"${pkg.srcDir.getPath}/${fileName(pkg)}"),
          prefixStage = true,
          prefixStack = true,
          prefixPackage = true,
          publicReadAcl = false
        )
      )
    }
    case "updateLambda" => (pkg) => (resourceLookup, parameters, stack) => {
      implicit val keyRing = resourceLookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      val stage = parameters.stage.name

      val functionName = s"${functionName(pkg)}$stage"
      // TODO: this has implicit knowledge of the way the S3Upload task assembles the key, which is bad
      val s3Key = List(stack.nameOption, Some(stage), Some(pkg.name), Some(fileName(pkg))).flatten.mkString("/")

      List(
        UpdateS3Lambda(
          functionName,
          bucket(pkg),
          s3Key
        )
      )
    }
  }
}
