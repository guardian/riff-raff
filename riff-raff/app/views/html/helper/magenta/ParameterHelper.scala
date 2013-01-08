package views.html.helper.magenta

import magenta.DeployParameters
import controllers.routes

object ParameterHelper {
  implicit def parameters2Calls(parameters: DeployParameters) = new {
    def previewContentCall =
      routes.Deployment.previewContent(
        parameters.build.projectName,
        parameters.build.id,
        parameters.stage.name,
        parameters.recipe.name,
        parameters.hostList.mkString(",")
      )
  }
}