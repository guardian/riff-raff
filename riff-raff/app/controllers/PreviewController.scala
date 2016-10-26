package controllers

import java.util.UUID

import cats.data.Validated.{Invalid, Valid}
import deployment.preview.PreviewCoordinator
import magenta.{Build, DeployParameters, Deployer, Stage}
import play.api.libs.ws.WSClient
import play.api.mvc.Controller

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PreviewController(coordinator: PreviewCoordinator)(
  implicit val wsClient: WSClient
) extends Controller with LoginActions {
  def preview(projectName: String, buildId: String, stage: String) = AuthAction { request =>
    val build = Build(projectName, buildId)
    val parameters = DeployParameters(Deployer(request.user.fullName), build, Stage(stage))
    coordinator.startPreview(parameters) match {
      case Right(uuid) => Ok(views.html.preview.yaml.preview(request, parameters, uuid.toString))
      case Left(error) =>
        // assume that this is not a YAML deployable and redirect to the legacy preview
        // if we came from the original process form then we'll have these extra params that we can use
        val recipe = request.flash.data.getOrElse("previewRecipe", parameters.recipe.name)
        val hosts = request.flash.data.getOrElse("previewHosts", parameters.hostList.mkString(","))
        val stacks = request.flash.data.getOrElse("previewStacks", "")
        Redirect(routes.DeployController.preview(parameters.build.projectName, parameters.build.id,
          parameters.stage.name, recipe, hosts, stacks))
    }
  }

  def showTasks(previewId: String) = AuthAction.async { request =>
    val maybeResult = coordinator.getPreviewResult(UUID.fromString(previewId))
    maybeResult match {
      case Some(result) if result.future.isCompleted =>
        result.future.map { preview =>
          preview.graph match {
            case Valid(taskGraph) => Ok(views.html.preview.yaml.showTasks(request, taskGraph))
            case Invalid(errors) => Ok(views.html.validation.validationErrors(request, errors))
          }
        }
      case Some(result) =>
        Future.successful(Ok(views.html.preview.yaml.loading(request, result.duration.getStandardSeconds)))
      case None =>
        Future.successful(NotFound(s"Preview with ID $previewId doesn't exist."))
    }
  }
}
