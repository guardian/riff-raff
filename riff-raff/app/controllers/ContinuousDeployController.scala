package controllers

import play.api.mvc.Controller
import play.api.data.Forms._
import play.api.data.Form
import persistence.{ContinuousDeploymentConfigRepository, Persistence}
import java.util.UUID

import ci.{ContinuousDeploymentConfig, Trigger}
import org.joda.time.DateTime
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.ws.WSClient
import play.filters.csrf.{CSRFAddToken, CSRFCheck}
import utils.Forms.uuid

class ContinuousDeployController(implicit val messagesApi: MessagesApi, val wsClient: WSClient) extends Controller with Logging with LoginActions with I18nSupport {
  import ContinuousDeployController._

  val continuousDeploymentForm = Form[ConfigForm](
    mapping(
      "id" -> uuid,
      "projectName" -> nonEmptyText,
      "stage" -> nonEmptyText,
      "recipe" -> nonEmptyText,
      "branchMatcher" -> optional(text),
      "trigger" -> number
    )(ConfigForm.apply)(ConfigForm.unapply)
  )

  def list = CSRFAddToken {
    AuthAction { implicit request =>
      val configs = ContinuousDeploymentConfigRepository.getContinuousDeploymentList().sortBy(q => (q.projectName, q.stage))
      Ok(views.html.continuousDeployment.list(request, configs))
    }
  }
  def form = CSRFAddToken {
    AuthAction { implicit request =>
      Ok(views.html.continuousDeployment.form(continuousDeploymentForm.fill(ConfigForm(UUID.randomUUID(),"","","default",None,Trigger.SuccessfulBuild.id))))
    }
  }
  def save = CSRFCheck { CSRFAddToken {
    AuthAction { implicit request =>
      continuousDeploymentForm.bindFromRequest().fold(
        formWithErrors => Ok(views.html.continuousDeployment.form(formWithErrors)),
        form => {
          val config = ContinuousDeploymentConfig(
            form.id, form.projectName, form.stage, form.recipe, form.branchMatcher, Trigger(form.trigger), request.user.fullName, new DateTime()
          )
          ContinuousDeploymentConfigRepository.setContinuousDeployment(config)
          Redirect(routes.ContinuousDeployController.list())
        }
      )
    }
  }}
  def edit(id: String) = CSRFAddToken {
    AuthAction { implicit request =>
      ContinuousDeploymentConfigRepository.getContinuousDeployment(UUID.fromString(id)).map{ config =>
        Ok(views.html.continuousDeployment.form(continuousDeploymentForm.fill(ConfigForm(config))))
      }.getOrElse(Redirect(routes.ContinuousDeployController.list()))
    }
  }
  def delete(id: String) = CSRFCheck {
    AuthAction { implicit request =>
      Form("action" -> nonEmptyText).bindFromRequest().fold(
        errors => {},
        {
          case "delete" =>
            ContinuousDeploymentConfigRepository.deleteContinuousDeployment(UUID.fromString(id))
        }
      )
      Redirect(routes.ContinuousDeployController.list())
    }
  }
}

object ContinuousDeployController {

  case class ConfigForm(id: UUID, projectName: String, stage: String, recipe: String, branchMatcher:Option[String], trigger: Int)
  object ConfigForm {
    def apply(cd: ContinuousDeploymentConfig): ConfigForm =
      ConfigForm(cd.id, cd.projectName, cd.stage, cd.recipe, cd.branchMatcher, cd.trigger.id)
  }

}

