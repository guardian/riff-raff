package magenta.deployment_type

import java.io.File
import magenta.tasks.UpdateLambda

object Lambda extends DeploymentType  {
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
      val fName = functionName(pkg)
      val stage = parameters.stage.name
      assert(functionName.get(pkg).isDefined, "functionName must be defined")
    List(UpdateLambda(new File(pkg.srcDir.getPath + "/lambda.zip"), s"$fName-$stage"))

    }
  }
}
