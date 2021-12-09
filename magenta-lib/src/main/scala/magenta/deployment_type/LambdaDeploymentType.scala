package magenta.deployment_type

import magenta.tasks.SSM
import magenta.{DeployReporter, DeployTarget, DeploymentPackage, DeploymentResources, KeyRing, Region}
import software.amazon.awssdk.services.ssm.SsmClient

trait LambdaDeploymentType[LAMBDA_TASK_PRECURSOR <: LambdaTaskPrecursor] extends DeploymentType {
  def functionNamesParamDescriptionSuffix: String
  def lambdaKeyword: String
  val lambdaKeywordPastTense = s"$lambdaKeyword${if (lambdaKeyword.endsWith("e")) "d" else "ed"}"
  val functionNamesParam = Param[List[String]]("functionNames",
    s"""One or more function names to $lambdaKeyword.
      |Each function name will be suffixed with the stage, e.g. MyFunction- becomes MyFunction-CODE""".stripMargin,
    optional = true
  )
  val lookupByTags = Param[Boolean]("lookupByTags",
    s"""When true, this will lookup the function to ${lambdaKeyword} to by using the Stack, Stage and App tags on a function.
      |The values looked up come from the `stacks` and `app` in the riff-raff.yaml and the stage ${lambdaKeywordPastTense} to.
    """.stripMargin
  ).default(false)

  val prefixStackParam = Param[Boolean]("prefixStack",
    s"If true then the values in the functionNames param will be prefixed with the name of the stack being ${lambdaKeywordPastTense}").default(true)

  val prefixStackToKeyParam = Param[Boolean]("prefixStackToKey",
    documentation = "Whether to prefix `package` to the S3 location"
  ).default(true)

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
  def withSsm[T](keyRing: KeyRing, region: Region, resources: DeploymentResources): (SsmClient => T) => T = SSM.withSsmClient[T](keyRing, region, resources)

  def makeS3Key(target: DeployTarget, pkg:DeploymentPackage, fileName: String, reporter: DeployReporter): String = {
    val prefixStack = prefixStackToKeyParam(pkg, target, reporter)
    val prefix = if (prefixStack) List(target.stack.name) else Nil
    (prefix ::: List(target.parameters.stage.name, pkg.app.name, fileName)).mkString("/")
  }

  def buildLambdaTaskPrecursor(stackNamePrefix: String, stage: String, name: String, pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter): LAMBDA_TASK_PRECURSOR
  def buildLambdaTaskPrecursor(functionName: String, functionDefinition: Map[String, String], pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter): LAMBDA_TASK_PRECURSOR
  def buildLambdaTaskPrecursor(tags: LambdaFunctionTags, pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter): LAMBDA_TASK_PRECURSOR

  def lambdaToProcess(pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter): List[LAMBDA_TASK_PRECURSOR] = {
    val stage = target.parameters.stage.name

    (functionNamesParam.get(pkg), functionsParam.get(pkg), lookupByTags(pkg, target, reporter), prefixStackParam(pkg, target, reporter)) match {
      // the lambdas are a simple hardcoded list of function names
      case (Some(functionNames), None, false, prefixStack) =>
        val stackNamePrefix = if (prefixStack) target.stack.name else ""
        functionNames.map { name =>
          buildLambdaTaskPrecursor(stackNamePrefix, stage, name, pkg, target, reporter)
        }

      // the list of lambdas are provided in a map from stage to lambda name and filename
      case (None, Some(functionsMap), false, _) =>
        val functionDefinition = functionsMap.getOrElse(stage, reporter.fail(s"Function not defined for stage $stage"))
        val functionName = functionDefinition.getOrElse("name", reporter.fail(s"Function name not defined for stage $stage"))

        List(buildLambdaTaskPrecursor(functionName, functionDefinition, pkg, target, reporter))

      // the lambda is discovered from Stack, App and Stage tags
      case (None, None, true, _) =>
        val tags = LambdaFunctionTags(Map(
          "Stack" -> target.stack.name,
          "App" -> pkg.app.name,
          "Stage" -> stage
        ))
        List(buildLambdaTaskPrecursor(tags, pkg, target, reporter))

      case _ => reporter.fail("Must specify one of 'functions', 'functionNames' or 'lookupByTags' parameters")
    }
  }
}

sealed trait LambdaFunction
case class LambdaFunctionName(name: String) extends LambdaFunction
case class LambdaFunctionTags(tags: Map[String, String]) extends LambdaFunction

trait LambdaTaskPrecursor