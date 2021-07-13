package magenta.deployment_type

import magenta.{DeployReporter, DeployTarget, DeploymentPackage, Region}
import magenta.tasks.InvokeLambda

object LambdaInvoke extends LambdaInvoke

case class InvokeLambdaFunction(function: LambdaFunction, region: Region) extends LambdaTaskPrecursor

trait LambdaInvoke extends LambdaDeploymentType[InvokeLambdaFunction] {
  override val functionNamesParamDescriptionSuffix = "invoke"

  val name = "aws-invoke-lambda"

  override def documentation: String = "Invokes Lambda"

  def buildLambdaTaskPrecursor(stackNamePrefix: String, stage: String, name: String, pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter) =
    InvokeLambdaFunction(LambdaFunctionName(s"$stackNamePrefix$name$stage"), target.region)

  def buildLambdaTaskPrecursor(functionName: String, functionDefinition: Map[String, String], pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter) =
    InvokeLambdaFunction(LambdaFunctionName(functionName), target.region)

  def buildLambdaTaskPrecursor(tags: LambdaFunctionTags, pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter) =
    InvokeLambdaFunction(tags, target.region)

  override def defaultActions: List[Action] = List(Action("invoke","Do nothing"){
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
  })
}