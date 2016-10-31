package controllers

import java.util.UUID

import cats.data.Validated.{Invalid, Valid}
import com.gu.management.Loggable
import controllers.forms.DeployParameterForm
import deployment.preview.PreviewCoordinator
import magenta.input.{DeploymentIdsFilter, NoFilter}
import magenta.{Build, DeployParameters, Deployer, Stage}
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.ws.WSClient
import play.api.mvc.Controller
import utils.Forms

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PreviewController(coordinator: PreviewCoordinator)(
  implicit val wsClient: WSClient, val messagesApi: MessagesApi
) extends Controller with LoginActions with I18nSupport with Loggable {
  def preview(projectName: String, buildId: String, stage: String, deployments: Option[String]) = AuthAction { request =>
    val build = Build(projectName, buildId)
    val filter = deployments.map(Forms.stringToIdList) match {
      case Some(head :: tail) => DeploymentIdsFilter(head :: tail)
      case _ => NoFilter
    }
    val parameters = DeployParameters(Deployer(request.user.fullName), build, Stage(stage), filter = filter)
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
              val deploymentIds = taskGraph.toList.map(_._1)
              val noFilterCount = preview.parameters.filter match {
                case NoFilter => Some(deploymentIds.size)
                case _ => None
              }
              logger.info(s"Deployment IDs: $deploymentIds")
              val form = DeployParameterForm.form.fill(
                DeployParameterForm(
                  preview.parameters.build.projectName,
                  preview.parameters.build.id,
                  preview.parameters.stage.name,
                  None,
                  "n/a",
                  Nil,
                  Nil,
                  deploymentIds,
                  noFilterCount
                ))
              Ok(views.html.preview.yaml.showTasks(taskGraph, form, deploymentIds))
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
