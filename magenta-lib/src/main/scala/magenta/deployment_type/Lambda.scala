package magenta.deployment_type

import java.io.File

import magenta.{DeployParameters, DeploymentPackage, KeyRing, MessageBroker, Stack, Stage}
import magenta.tasks.{S3UploadV2, UpdateLambda, UpdateS3Lambda}

object Lambda extends DeploymentType  {
  val name = "aws-lambda"
  val documentation =
    """
      |Provides deploy actions to upload and update Lambda functions. This deployment type can with with or without S3.
      |When using S3 you should use both the `uploadLambda` and `updateLambda`. When not using S3 `uploadLambda` is
      |no-op.
      |
      |It is recommended that you only use the `functionName` and `fileName` parameters. In this case the `functionName`
      |will be appended with the stage you are deploying to and the file uploaded will be the same for all stages.
      |
      |Due to the current limitations in AWS (particularly the lack of configuration mechanisms) there is a more
      |powerful `functions` parameter. This lets you bind a specific file to a specific function for any given stage.
      |As a result you can bundle stage specific configuration into the respective files.
    """.stripMargin

  val bucketParam = Param[String]("bucket",
    documentation =
      """
        |Name of the S3 bucket where the lambda archive should be uploaded - if this is not specified then the zip file
        |will be uploaded in the Lambda Update Function Code request
      """.stripMargin
  )

  val functionNameParam = Param[String]("functionName",
    "The stub of the function name (will be suffixed with the stage, e.g. MyFunction- becomes MyFunction-CODE)")

  val fileNameParam = Param[String]("fileName", "The name of the archive of the function")
    .defaultFromPackage(pkg => s"${pkg.name}.zip")

  val functionsParam = Param[Map[String, Map[String, String]]]("functions",
    documentation =
      """
        |In order for this to work, magenta must have credentials that are able to perform `lambda:UpdateFunctionCode`
        |on the specified resources.
        |
        |Map of Stage to Lambda functions. `name` is the Lambda `FunctionName`. The `filename` field is optional and if
        |not specified defaults to `lambda.zip`
        |e.g.
        |
        |        "functions": {
        |          "CODE": {
        |           "name": "myLambda-CODE",
        |           "filename": "myLambda-CODE.zip",
        |          },
        |          "PROD": {
        |           "name": "myLambda-PROD",
        |           "filename": "myLambda-PROD.zip",
        |          }
        |        }
      """.stripMargin
  )

  def lambdaToProcess(pkg: DeploymentPackage, stage: String): LambdaFunction = {
    val bucketOption = bucketParam.get(pkg)
    (functionNameParam.get(pkg), functionsParam.get(pkg)) match {
      case (Some(functionName), None) =>
        LambdaFunction(s"$functionName$stage", fileNameParam(pkg), bucketOption)
      case (None, Some(functionsMap)) =>
        val functionDefinition = functionsMap.getOrElse(stage, MessageBroker.fail(s"Function not defined for stage $stage"))
        val functionName = functionDefinition.getOrElse("name", MessageBroker.fail(s"Function name not defined for stage $stage"))
        val fileName = functionDefinition.getOrElse("filename", "lambda.zip")
        LambdaFunction(functionName, fileName, bucketOption)
      case _ => MessageBroker.fail("Must specify one of 'functions' or 'functionName' parameters")
    }
  }

  def makeS3Key(stack: Stack, params:DeployParameters, pkg:DeploymentPackage, fileName: String): String = {
    List(stack.nameOption, Some(params.stage.name), Some(pkg.name), Some(fileName)).flatten.mkString("/")
  }

  def perAppActions = {
    case "uploadLambda" => (pkg) => (resourceLookup, parameters, stack) => {
      implicit val keyRing = resourceLookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      lambdaToProcess(pkg, parameters.stage.name) match {
        case LambdaFunctionFromZip(_,_) => List()

        case LambdaFunctionFromS3(functionName, fileName, s3Bucket) =>
          val s3Key = makeS3Key(stack, parameters, pkg, fileName)
          List(S3UploadV2(
            s3Bucket,
            Seq((new File(s"${pkg.srcDir.getPath}/$fileName") -> s3Key))
          ))
      }
    }
    case "updateLambda" => (pkg) => (resourceLookup, parameters, stack) => {
      implicit val keyRing = resourceLookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      lambdaToProcess(pkg, parameters.stage.name) match {
        case LambdaFunctionFromZip(functionName, fileName) =>
          List(UpdateLambda(new File(s"${pkg.srcDir.getPath}/$fileName"), functionName))

        case LambdaFunctionFromS3(functionName, fileName, s3Bucket) =>
          val s3Key = makeS3Key(stack, parameters, pkg, fileName)
          List(
          UpdateS3Lambda(
            functionName,
            s3Bucket,
            s3Key
          ))
      }
    }
  }
}

sealed trait LambdaFunction {
  def functionName: String
  def fileName: String
}

case class LambdaFunctionFromS3(functionName: String, fileName: String, s3Bucket: String) extends LambdaFunction
case class LambdaFunctionFromZip(functionName: String, fileName: String) extends LambdaFunction

object LambdaFunction {
  def apply(functionName: String, fileName: String, s3Bucket: Option[String] = None): LambdaFunction = {
    s3Bucket.fold[LambdaFunction]{
      LambdaFunctionFromZip(functionName, fileName)
    }{ bucket =>
      LambdaFunctionFromS3(functionName, fileName, bucket)
    }
  }
}