package magenta.deployment_type

import java.io.File

import magenta.{DeployReporter, DeployParameters, DeploymentPackage, KeyRing, Stack, Stage}
import magenta.tasks.{S3Upload, UpdateLambda, UpdateS3Lambda}

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

  val functionNamesParam = Param[List[String]]("functionNames",
    """One or more function names to update with the code from fileNameParam.
      |Each function name will be suffixed with the stage, e.g. MyFunction- becomes MyFunction-CODE""".stripMargin
  )

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

  def lambdaToProcess(pkg: DeploymentPackage, stage: String)(reporter: DeployReporter): List[LambdaFunction] = {
    val bucketOption = bucketParam.get(pkg)
    (functionNamesParam.get(pkg), functionsParam.get(pkg)) match {
      case (Some(functionNames), None) =>
        functionNames.map(name => LambdaFunction(s"$name$stage", fileNameParam(pkg), bucketOption))
      case (None, Some(functionsMap)) =>
        val functionDefinition = functionsMap.getOrElse(stage, reporter.fail(s"Function not defined for stage $stage"))
        val functionName = functionDefinition.getOrElse("name", reporter.fail(s"Function name not defined for stage $stage"))
        val fileName = functionDefinition.getOrElse("filename", "lambda.zip")
        List(LambdaFunction(functionName, fileName, bucketOption))
      case _ => reporter.fail("Must specify one of 'functions' or 'functionNames' parameters")
    }
  }

  def makeS3Key(stack: Stack, params:DeployParameters, pkg:DeploymentPackage, fileName: String): String = {
    List(stack.nameOption, Some(params.stage.name), Some(pkg.name), Some(fileName)).flatten.mkString("/")
  }

  def perAppActions = {
    case "uploadLambda" => (pkg) => (deployLogger, resourceLookup, parameters, stack) => {
      implicit val keyRing = resourceLookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      lambdaToProcess(pkg, parameters.stage.name)(deployLogger).flatMap {
        case LambdaFunctionFromZip(_,_) => None

        case LambdaFunctionFromS3(functionName, fileName, s3Bucket) =>
          val s3Key = makeS3Key(stack, parameters, pkg, fileName)
          Some(S3Upload(
            s3Bucket,
            Seq(new File(s"${pkg.srcDir.getPath}/$fileName") -> s3Key)
          ))
      }.distinct
    }
    case "updateLambda" => (pkg) => (deployLogger, resourceLookup, parameters, stack) => {
      implicit val keyRing = resourceLookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      lambdaToProcess(pkg, parameters.stage.name)(deployLogger).flatMap {
        case LambdaFunctionFromZip(functionName, fileName) =>
          Some(UpdateLambda(new File(s"${pkg.srcDir.getPath}/$fileName"), functionName))

        case LambdaFunctionFromS3(functionName, fileName, s3Bucket) =>
          val s3Key = makeS3Key(stack, parameters, pkg, fileName)
          Some(
          UpdateS3Lambda(
            functionName,
            s3Bucket,
            s3Key
          ))
      }.distinct
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