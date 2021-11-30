package magenta.deployment_type

import play.api.libs.json.{JsArray, JsString, JsBoolean}

object BrazePublish extends LambdaInvoke {
  val brazePublishLambdaNameMinusStage = s"${LambdaInvoke.lambdaFunctionNamePrefix}braze-version-control-publish-lambda-"
  override val name = "braze-publish"
  private val summary = s"Invokes Lambda `${brazePublishLambdaNameMinusStage}` (with the STAGE appended). This Lambda publishes content blocks to the target Braze environment (only if there are changes)."

  override def documentation: String = s"""
      |${summary}
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

  override def defaultActions: List[Action] = super.defaultActions.map { action =>
    action.copy(name="brazePublish", documentation=summary){(pkg, resources, target) =>
      action.taskGenerator(
        pkg.copy(pkgSpecificData = pkg.pkgSpecificData ++ Map(
          functionNamesParam.name -> JsArray(Array(JsString(brazePublishLambdaNameMinusStage))),
          prefixStackParam.name -> JsBoolean(false)
          //TODO: Filter out other Lambda params to avoid misuse
        )),
        resources,
        target
      )
    }
  }

  override def paramsToHide: Seq[Param[_]] = super.params
}