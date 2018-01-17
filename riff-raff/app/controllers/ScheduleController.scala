package controllers

import play.api.mvc.{AnyContent, BaseController, ControllerComponents}
import play.api.data.Forms._
import play.api.data.Form
import play.api.i18n.I18nSupport
import play.api.libs.ws.WSClient
import java.util.UUID

import org.joda.time.DateTime
import ci.{ContinuousDeploymentConfig, Trigger}
import com.gu.googleauth.AuthAction
import controllers.ContinuousDeployController.ConfigForm
import controllers.ScheduleController.Schedule
import persistence.{ContinuousDeploymentConfigRepository, ScheduleRepository}
import resources.PrismLookup
import schedule.ScheduleConfig

class ScheduleController(authAction: AuthAction[AnyContent], val controllerComponents: ControllerComponents, prismLookup: PrismLookup)(implicit val wsClient: WSClient)
  extends BaseController with Logging with I18nSupport {

  import ScheduleController.ScheduleForm

  val scheduleForm = Form[ScheduleForm](
    mapping(
      "id" -> uuid,
      "projectName" -> nonEmptyText,
      "stage" -> nonEmptyText,
      "schedule" -> nonEmptyText.transform[Schedule](Schedule.apply, _.expression),
      "enabled" -> boolean
    )(ScheduleForm.apply)(ScheduleForm.unapply)
  )

  def list = authAction { implicit request =>
    val schedules = ScheduleRepository.getScheduleList()
    Ok(views.html.schedule.list(request, schedules))
  }

  def form = authAction { implicit request =>
    Ok(views.html.schedule.form(
      scheduleForm.fill(ScheduleForm(UUID.randomUUID(), "", "", Schedule(""), enabled = true)), prismLookup
    ))
  }

  def save = authAction { implicit request =>
    scheduleForm.bindFromRequest().fold(
      formWithErrors => Ok(views.html.schedule.form(formWithErrors, prismLookup)),
      form => {
        ScheduleRepository.setSchedule(form.toConfig(new DateTime(), request.user.fullName))
        Redirect(routes.ScheduleController.list())
      }
    )
  }

  def edit(id: String) = authAction { implicit request =>
    ScheduleRepository.getSchedule(UUID.fromString(id))
        .fold(NotFound(s"Schedule with ID $id doesn't exist"))(
          config => Ok(views.html.schedule.form(scheduleForm.fill(ScheduleForm(config)), prismLookup))
        )
  }

  def delete(id: String) = authAction { implicit request =>
    Form("action" -> nonEmptyText).bindFromRequest().fold(
      errors => {},
      {
        case "delete" =>
          ScheduleRepository.deleteSchedule(UUID.fromString(id))
      }
    )
    Redirect(routes.ScheduleController.list())
  }

}

object ScheduleController {

  case class Schedule(expression: String)

  case class ScheduleForm(id: UUID, projectName: String, stage: String, schedule: Schedule, enabled: Boolean) {
    def toConfig(lastEdited: DateTime, user: String): ScheduleConfig =
      ScheduleConfig(id, projectName, stage, schedule, enabled, lastEdited, user)
  }
  object ScheduleForm {
    def apply(config: ScheduleConfig): ScheduleForm =
      ScheduleForm(config.id, config.projectName, config.stage, config.schedule, config.enabled)
  }

}

