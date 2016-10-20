package views.html.helper.magenta

import magenta.DeployParameters
import controllers.routes

object ParameterHelper {
  implicit class RichDeployParameters(parameters: DeployParameters) {
    def previewContentCall(id: String) =
      routes.DeployController.previewContent(
        id.toString,
        parameters.build.projectName,
        parameters.build.id,
        parameters.stage.name,
        parameters.recipe.name,
        parameters.hostList.mkString(",")
      )
  }
}