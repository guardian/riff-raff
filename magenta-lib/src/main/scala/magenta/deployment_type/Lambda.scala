package magenta.deployment_type

import java.io.File
import magenta.MessageBroker
import magenta.tasks.UpdateLambda

object Lambda extends DeploymentType  {
  val name = "aws-lambda"
  val documentation =
    """
      |Provides one deploy action, `updateLambda`, that runs Lambda Update Function Code using the package file which should be a single file named lambda.zip.
    """.stripMargin
  

  //required configuration, you cannot upload without setting these
  val functionNames = Param[Map[String, String]]("functionNames",
    documentation =
      """Map of Stage to Lambda function names.
        |e.g.
        |        "functionNames": {
        |          "CODE": 'myLambda-CODE',
        |          "PROD": 'myLambda-PROD'
        |        }
      """.stripMargin
  ).default(Map.empty)

  def perAppActions = {
    case "updateLambda" => (pkg) => (resourceLookup, parameters, stack) => {
      implicit val keyRing = resourceLookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      val fNames = functionNames(pkg)
      val stage = parameters.stage.name
      val fName = fNames get stage
      fName match{
        case None => MessageBroker.fail(s"functionName must be defined for stage $stage")
        case Some(fName) => List(UpdateLambda(new File(pkg.srcDir.getPath + "/lambda.zip"), fName))
      }
    List(UpdateLambda(new File(pkg.srcDir.getPath + "/lambda.zip"), fName.get))

    }
  }
}
