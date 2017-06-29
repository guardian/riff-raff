package controllers

import java.util.UUID

import cats.data.Validated.{Invalid, Valid}
import com.gu.management.Loggable
import controllers.forms.DeployParameterForm
import deployment.preview.PreviewCoordinator
import magenta.input.{All, DeploymentKey, DeploymentKeysSelector}
import magenta.{Build, DeployParameters, Deployer, Stage}
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.ws.WSClient
import play.api.mvc.{BaseController, Controller, ControllerComponents}
import utils.Forms

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PreviewController(coordinator: PreviewCoordinator, val controllerComponents: ControllerComponents)(
  implicit val wsClient: WSClient
) extends BaseController with LoginActions with I18nSupport with Loggable {
  def preview(projectName: String, buildId: String, stage: String, deployments: Option[String]) = AuthAction { request =>
    val build = Build(projectName, buildId)
    val selector = deployments.map(DeploymentKey.fromStringToList) match {
      case Some(head :: tail) => DeploymentKeysSelector(head :: tail)
      case _ => All
    }
    val parameters = DeployParameters(Deployer(request.user.fullName), build, Stage(stage), selector = selector)
    coordinator.startPreview(parameters) match {
      case Right(id) => Ok(views.html.preview.yaml.preview(request, parameters, id.toString))
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

  def showTasks(previewId: String) = AuthAction.async { implicit request =>
    val maybeResult = coordinator.getPreviewResult(UUID.fromString(previewId))
    maybeResult match {
      case Some(result) if result.future.isCompleted =>
        result.future.map { preview =>
          preview.graph match {
            case Valid(taskGraph) =>
              val deploymentKeys = taskGraph.toList.map(_._1)
              val totalKeyCount = preview.parameters.selector match {
                case All => Some(deploymentKeys.size)
                case _ => None
              }
              logger.info(s"Deployment keys: $deploymentKeys")
              val form = DeployParameterForm.form.fill(
                DeployParameterForm(
                  preview.parameters.build.projectName,
                  preview.parameters.build.id,
                  preview.parameters.stage.name,
                  None,
                  "n/a",
                  Nil,
                  Nil,
                  deploymentKeys,
                  totalKeyCount
                ))
              Ok(views.html.preview.yaml.showTasks(taskGraph, form, deploymentKeys))
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
