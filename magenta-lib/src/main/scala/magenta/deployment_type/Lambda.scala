package magenta.deployment_type

import com.amazonaws.regions.{Regions => AwsRegions, Region => AwsRegion}
import magenta.artifact.S3Path
import magenta.tasks.{S3Upload, UpdateLambda, UpdateS3Lambda}
import magenta.{DeployParameters, DeployReporter, DeploymentPackage, Region, Stack}

import scala.util.Try

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

  val regionsParam = Param[List[String]]("regions",
    documentation = 
      """
      |One or more AWS region name where the lambda should be deployed - If this is not specified then the default region will be used (typically set to eu-west-1).
      """.stripMargin
  )

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

  val prefixStackParam = Param[Boolean]("prefixStack",
    "If true then the values in the functionNames param will be prefixed with the name of the stack being deployed").default(false)

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

  def defaultActions = List("uploadLambda", "updateLambda")

  def lambdaToProcess(pkg: DeploymentPackage, stage: String, stack: Stack, targetRegion: Region)(reporter: DeployReporter): List[LambdaFunction] = {
    val bucketOption = bucketParam.get(pkg)
    if (bucketOption.isEmpty) reporter.warning(s"DEPRECATED: Uploading directly to lambda is deprecated (it is dangerous for CloudFormed lambdas). Specify the bucket parameter and call both uploadLambda and updateLambda to upload via S3.")
    
    val regions: List[Region] = regionsParam.get(pkg).map(_.filter(regionExists(_)(reporter)).map(Region)).getOrElse(List(targetRegion))

    (functionNamesParam.get(pkg), functionsParam.get(pkg), prefixStackParam(pkg)) match {
      case (Some(functionNames), None, prefixStack) =>
        val stackNamePrefix = stack.nameOption.filter(_ => prefixStack).getOrElse("")
        for {
          name <- functionNames
          region <- regions
        } yield LambdaFunction(s"$stackNamePrefix$name$stage", fileNameParam(pkg), region, bucketOption)
        
      case (None, Some(functionsMap), _) =>
        val functionDefinition = functionsMap.getOrElse(stage, reporter.fail(s"Function not defined for stage $stage"))
        val functionName = functionDefinition.getOrElse("name", reporter.fail(s"Function name not defined for stage $stage"))
        val fileName = functionDefinition.getOrElse("filename", "lambda.zip")
        for {
          region <- regions
        } yield LambdaFunction(functionName, fileName, region, bucketOption)

      case _ => reporter.fail("Must specify one of 'functions' or 'functionNames' parameters")
    }
  }

  private def regionExists(name: String)(reporter: DeployReporter): Boolean = {
    /* sdk can throw IllegalArgumentException if region name is unknown */
    val exists = Try(AwsRegion.getRegion(AwsRegions.fromName(name))).toOption.isDefined
    if (!exists) {
      reporter.warning(s"$name is not a recognised AWS region and has been ignored.")
    }
    exists
  }

  def makeS3Key(stack: Stack, params:DeployParameters, pkg:DeploymentPackage, fileName: String): String = {
    List(stack.nameOption, Some(params.stage.name), Some(pkg.name), Some(fileName)).flatten.mkString("/")
  }

  def actions = {
    case "uploadLambda" => (pkg) => (resources, target) => {
      implicit val keyRing = resources.assembleKeyring(target, pkg)
      implicit val artifactClient = resources.artifactClient
      lambdaToProcess(pkg, target.parameters.stage.name, target.stack, target.region)(resources.reporter).flatMap {
        case LambdaFunctionFromZip(_,_, _) => None

        case LambdaFunctionFromS3(functionName, fileName, region, s3Bucket) =>
          val s3Key = makeS3Key(target.stack, target.parameters, pkg, fileName)
          Some(S3Upload(
            target.region,
            s3Bucket,
            Seq(S3Path(pkg.s3Package, fileName) -> s3Key)
          ))
      }.distinct
    }
    case "updateLambda" => (pkg) => (resources, target) => {
      implicit val keyRing = resources.assembleKeyring(target, pkg)
      implicit val artifactClient = resources.artifactClient
      lambdaToProcess(pkg, target.parameters.stage.name, target.stack, target.region)(resources.reporter).flatMap {
        case LambdaFunctionFromZip(functionName, fileName, region) =>
          Some(UpdateLambda(S3Path(pkg.s3Package,fileName), functionName, region))

        case LambdaFunctionFromS3(functionName, fileName, region, s3Bucket) =>
          val s3Key = makeS3Key(target.stack, target.parameters, pkg, fileName)
          Some(
          UpdateS3Lambda(
            functionName,
            s3Bucket,
            s3Key,
            region
          ))
      }.distinct
    }
  }
}

sealed trait LambdaFunction {
  def functionName: String
  def fileName: String
  def region: Region
}

case class LambdaFunctionFromS3(functionName: String, fileName: String, region: Region, s3Bucket: String) extends LambdaFunction
case class LambdaFunctionFromZip(functionName: String, fileName: String, region: Region) extends LambdaFunction

object LambdaFunction {
  def apply(functionName: String, fileName: String, region: Region, s3Bucket: Option[String] = None): LambdaFunction = {
    s3Bucket.fold[LambdaFunction]{
      LambdaFunctionFromZip(functionName, fileName, region)
    }{ bucket =>
      LambdaFunctionFromS3(functionName, fileName, region, bucket)
    }
  }
}