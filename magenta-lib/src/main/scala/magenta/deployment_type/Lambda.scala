package magenta.deployment_type

import java.io.File
import magenta.tasks.{UpdateLambda, Lambda}

object Lambda extends DeploymentType with S3AclParams  {
  val name = "aws-lambda"
  val documentation =
    """
      |Provides one deploy action, `updateLambda`, that runs Lambda Update Function Code using the package file which should be a single .zip file.
    """.stripMargin
  

  //required configuration, you cannot upload without setting these
  val functionName = Param[String]("functionName", "Lambda function name")

  def perAppActions = {
    case "updateLambda" => (pkg) => (resourceLookup, parameters, stack) => {
      implicit val keyRing = resourceLookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      assert(functionName.get(pkg).isDefined, "functionName must be defined"))
  List(UpdateLambda)

    }
  }
}
