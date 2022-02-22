package magenta.deployment_type

import magenta.deployment_type.LambdaInvoke.lambdaFunctionNamePrefix
import magenta.{DeployReporter, DeployTarget, DeploymentPackage, Region}
import magenta.tasks.InvokeLambda

case class InvokeLambdaFunction(function: LambdaFunction, region: Region) extends LambdaTaskPrecursor

object LambdaInvoke extends LambdaInvoke {
  val lambdaFunctionNamePrefix = "RIFF-RAFF-INVOKABLE-"

  val invokeAction = getInvokeAction
  override def defaultActions: List[Action] = List(invokeAction)
}

trait LambdaInvoke extends LambdaDeploymentType[InvokeLambdaFunction] {
  override def lambdaKeyword: String = "invoke"
  override def functionNamesParamDescriptionSuffix = lambdaKeyword

  val name = "aws-invoke-lambda"
  private val summary = s"Invokes Lambda(s), selected using the parameters below (similar to the way the `${LambdaDeploy.name}` deployment type finds Lambdas)."

  val documentation: String =
    s"""
      |${summary}
      |
      |Lambda function names must begin with `${lambdaFunctionNamePrefix}`.
      |
      |The payload sent to the Lambda is constructed from the artifacts associated with this deployment step.
      |The top level key is the name of the deployment step, and the keys of the object within are the file names and the values are the file contents as strings.
      |
      |For example (given the name of the deployment step is 'hello_world' in the `riff-raff.yaml`), the payload would look something like:
      |```
      |{
      |  "hello_world": {
      |    "artefact_filenameA.abc" : "file A contents",
      |    "artefact_filenameB.abc" : "file B contents",
      |    "artefact_filenameC.abc" : "file C contents"
      |  }
      |}
      |```
    """.stripMargin

  def buildLambdaTaskPrecursor(stackNamePrefix: String, stage: String, name: String, pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter) =
    InvokeLambdaFunction(LambdaFunctionName(s"$stackNamePrefix$name$stage"), target.region)

  def buildLambdaTaskPrecursor(functionName: String, functionDefinition: Map[String, String], pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter) =
    InvokeLambdaFunction(LambdaFunctionName(functionName), target.region)

  def buildLambdaTaskPrecursor(tags: LambdaFunctionTags, pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter) =
    InvokeLambdaFunction(tags, target.region)

  def getInvokeAction = Action(name = "invokeLambda",documentation = summary){
    (pkg, resources, target) => {
      lambdaToProcess(pkg, target, resources.reporter).map { lambda =>
        InvokeLambda(
          function = lambda.function,
          artifactsPath = pkg.s3Package,
          region = lambda.region
        )(
          keyRing = resources.assembleKeyring(target, pkg)
        )
      }
    }
  }

}