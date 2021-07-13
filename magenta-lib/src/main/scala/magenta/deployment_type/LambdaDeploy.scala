package magenta.deployment_type

import magenta.artifact.S3Path
import magenta.tasks.S3.Bucket
import magenta.tasks.{S3Upload, SSM, STS, UpdateS3Lambda, S3 => S3Tasks}
import magenta.{DeployParameters, DeployReporter, DeployTarget, DeploymentPackage, DeploymentResources, KeyRing, Region, Stack}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.ssm.SsmClient

object LambdaDeploy extends LambdaDeploy

trait LambdaDeploy extends LambdaDeploymentType {
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

  val fileNameParam = Param[String]("fileName", "The name of the archive of the function", deprecatedDefault = true)
    .defaultFromContext((pkg, _) => Right(s"${pkg.name}.zip"))

  override val functionNamesParamDescriptionSuffix = "update with the code from fileNameParam"

  def buildUpdateLambdaFunction(stackNamePrefix: String, stage: String, name: String, pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter) =
    UpdateLambdaFunction(LambdaFunctionName(s"$stackNamePrefix$name$stage"), fileNameParam(pkg, target, reporter), target.region, getTargetBucketFromConfig(pkg, target, reporter))

  def buildUpdateLambdaFunction(functionName: String, functionDefinition: Map[String, String], pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter) =
    UpdateLambdaFunction(LambdaFunctionName(functionName), fileName = functionDefinition.getOrElse("filename", "lambda.zip"), target.region, getTargetBucketFromConfig(pkg, target, reporter))

  // TODO: Introduce parent trait for LambdaDeploy and LambdaInvoke to inherit from which holds shared behaviour such as this function
  def lambdaToProcess(pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter): List[UpdateLambdaFunction] = {
    val stage = target.parameters.stage.name

    (functionNamesParam.get(pkg), functionsParam.get(pkg), lookupByTags(pkg, target, reporter), prefixStackParam(pkg, target, reporter)) match {
      // the lambdas are a simple hardcoded list of function names
      case (Some(functionNames), None, false, prefixStack) =>
        val stackNamePrefix = if (prefixStack) target.stack.name else ""
        functionNames.map { name =>
          buildUpdateLambdaFunction(stackNamePrefix, stage, name, pkg, target, reporter)
        }

      // the list of lambdas are provided in a map from stage to lambda name and filename
      case (None, Some(functionsMap), false, _) =>
        val functionDefinition = functionsMap.getOrElse(stage, reporter.fail(s"Function not defined for stage $stage"))
        val functionName = functionDefinition.getOrElse("name", reporter.fail(s"Function name not defined for stage $stage"))

        List(buildUpdateLambdaFunction(functionName, functionDefinition, pkg, target, reporter))

      // the lambda to update is discovered from Stack, App and Stage tags
      case (None, None, true, _) =>
        val tags = LambdaFunctionTags(Map(
          "Stack" -> target.stack.name,
          "App" -> pkg.app.name,
          "Stage" -> stage
        ))
        List(UpdateLambdaFunction(tags, fileNameParam(pkg, target, reporter), target.region, bucket))

      case _ => reporter.fail("Must specify one of 'functions', 'functionNames' or 'lookupByTags' parameters")
    }
  }

  def makeS3Key(target: DeployTarget, pkg:DeploymentPackage, fileName: String, reporter: DeployReporter): String = {
    val prefixStack = prefixStackToKeyParam(pkg, target, reporter)
    val prefix = if (prefixStack) List(target.stack.name) else Nil
    (prefix ::: List(target.parameters.stage.name, pkg.app.name, fileName)).mkString("/")
  }

  def withSsm[T](keyRing: KeyRing, region: Region, resources: DeploymentResources): (SsmClient => T) => T = SSM.withSsmClient[T](keyRing, region, resources)

  val uploadLambda = Action("uploadLambda",
    """
      |Uploads the lambda code to S3.
    """.stripMargin){ (pkg, resources, target) =>
    implicit val keyRing: KeyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient: S3Client = resources.artifactClient
    lambdaToProcess(pkg, target, resources.reporter).map { lambda =>
      val s3Bucket = S3Tasks.getBucketName(
        lambda.s3Bucket,
        withSsm(keyRing, target.region, resources),
        resources.reporter
      )
      val s3Key = makeS3Key(target, pkg, lambda.fileName, resources.reporter)
      S3Upload(
        lambda.region,
        s3Bucket,
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
        val s3Bucket = S3Tasks.getBucketName(
          lambda.s3Bucket,
          withSsm(keyRing, target.region, resources),
          resources.reporter
        )
        val s3Key = makeS3Key(target, pkg, lambda.fileName, resources.reporter)
        UpdateS3Lambda(
          lambda.function,
          s3Bucket,
          s3Key,
          lambda.region
        )
    }.distinct
  }

  def defaultActions = List(uploadLambda, updateLambda)
}

sealed trait LambdaFunction
case class LambdaFunctionName(name: String) extends LambdaFunction
case class LambdaFunctionTags(tags: Map[String, String]) extends LambdaFunction
case class UpdateLambdaFunction(function: LambdaFunction, fileName: String, region: Region, s3Bucket: S3Tasks.Bucket)