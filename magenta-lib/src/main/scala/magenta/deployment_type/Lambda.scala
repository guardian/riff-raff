package magenta.deployment_type

import magenta.artifact.S3Path
import magenta.tasks.{S3Upload, UpdateS3Lambda}
import magenta.{DeployParameters, DeployReporter, DeployTarget, DeploymentPackage, KeyRing, Region, Stack}
import software.amazon.awssdk.services.s3.S3Client

object Lambda extends DeploymentType  {
  val name = "aws-lambda"
  val documentation =
    """
      |Provides deploy actions to upload and update Lambda functions. This deployment type can with with or without S3.
      |When using S3 you should use both the `uploadLambda` and `updateLambda`.
      |
      |It is recommended to use the `bucket` parameter as storing the function code in S3 works much better when using
      |cloudformation.
      |
      |Ensure to add any relevant dependencies to your deploy in your riff-raff.yaml to guard against race conditions,
      |such as a Riff-Raff Cloudformation update modifying your lambda at the same time this deployment type is running.
      |
      |
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
      |Each function name will be suffixed with the stage, e.g. MyFunction- becomes MyFunction-CODE""".stripMargin,
    optional = true
  )

  val prefixStackParam = Param[Boolean]("prefixStack",
    "If true then the values in the functionNames param will be prefixed with the name of the stack being deployed").default(true)

  val fileNameParam = Param[String]("fileName", "The name of the archive of the function", deprecatedDefault = true)
    .defaultFromContext((pkg, _) => Right(s"${pkg.name}.zip"))

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
      """.stripMargin,
    optional = true
  )

  def lambdaToProcess(pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter): List[LambdaFunction] = {
    val bucket = bucketParam(pkg, target, reporter)

    val stage = target.parameters.stage.name

    (functionNamesParam.get(pkg), functionsParam.get(pkg), prefixStackParam(pkg, target, reporter)) match {
      case (Some(functionNames), None, prefixStack) =>
        val stackNamePrefix = if (prefixStack) target.stack.name else ""
        for {
          name <- functionNames
        } yield LambdaFunction(s"$stackNamePrefix$name$stage", fileNameParam(pkg, target, reporter), target.region, bucket)
        
      case (None, Some(functionsMap), _) =>
        val functionDefinition = functionsMap.getOrElse(stage, reporter.fail(s"Function not defined for stage $stage"))
        val functionName = functionDefinition.getOrElse("name", reporter.fail(s"Function name not defined for stage $stage"))
        val fileName = functionDefinition.getOrElse("filename", "lambda.zip")
        List(LambdaFunction(functionName, fileName, target.region, bucket))

      case _ => reporter.fail("Must specify one of 'functions' or 'functionNames' parameters")
    }
  }

  def makeS3Key(stack: Stack, params:DeployParameters, pkg:DeploymentPackage, fileName: String): String = {
    List(stack.name, params.stage.name, pkg.app.name, fileName).mkString("/")
  }

  val uploadLambda = Action("uploadLambda",
    """
      |Uploads the lambda code to S3.
    """.stripMargin){ (pkg, resources, target) =>
    implicit val keyRing: KeyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient: S3Client = resources.artifactClient
    lambdaToProcess(pkg, target, resources.reporter).map { lambda =>
      val s3Key = makeS3Key(target.stack, target.parameters, pkg, lambda.fileName)
      S3Upload(
        lambda.region,
        lambda.s3Bucket,
        Seq(S3Path(pkg.s3Package, lambda.fileName) -> s3Key)
      )
    }.distinct
  }
  val updateLambda = Action("updateLambda",
    """
      |Updates the lambda to use new code using the UpdateFunctionCode API.
      |
      |This copies the new function code from S3 (where it is stored by the `uploadLambda` action).
      |
      |The function name to update is determined by the `functionName` or `functions` parameters.
      |
      |It is recommended that you only use the `functionName` parameter (in combination with `fileName`). In this case
      |the `functionName` will be prefixed with the stack (default in YAML) and suffixed with the stage you are
      |deploying to and the file uploaded will be the same for all stack and stage combinations.
      |
      |Due to the current limitations in AWS (particularly the lack of configuration mechanisms) there is a more
      |powerful `functions` parameter. This lets you bind a specific file to a specific function for any given stage.
      |As a result you can bundle stage specific configuration into the respective files.
    """.stripMargin){ (pkg, resources, target) =>
    implicit val keyRing: KeyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient: S3Client = resources.artifactClient
    lambdaToProcess(pkg, target, resources.reporter).map { lambda =>
        val s3Key = makeS3Key(target.stack, target.parameters, pkg, lambda.fileName)
        UpdateS3Lambda(
          lambda.functionName,
          lambda.s3Bucket,
          s3Key,
          lambda.region
        )
    }.distinct
  }

  def defaultActions = List(uploadLambda, updateLambda)
}

case class LambdaFunction(functionName: String, fileName: String, region: Region, s3Bucket: String)