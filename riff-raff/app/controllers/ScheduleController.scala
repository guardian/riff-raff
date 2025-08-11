package controllers

import java.text.ParseException
import java.util.{TimeZone, UUID}
import com.gu.googleauth.AuthAction
import conf.Config
import org.joda.time.DateTime
import org.quartz.CronExpression
import persistence.ScheduleRepository
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.validation.{Constraint, Invalid, Valid}
import play.api.i18n.I18nSupport
import play.api.libs.ws.WSClient
import play.api.mvc.{ActionBuilder, AnyContent, BaseController, ControllerComponents}
import resources.PrismLookup
import schedule.{DeployScheduler, ScheduleConfig}

import scala.util.{Failure, Success, Try}

class ScheduleController(
    config: Config,
    menu: Menu,
    authAction: ActionBuilder[AuthAction.UserIdentityRequest, AnyContent],
    val controllerComponents: ControllerComponents,
    scheduleRepository: ScheduleRepository,
    prismLookup: PrismLookup,
    deployScheduler: DeployScheduler
)(implicit val wsClient: WSClient)
    extends BaseController
    with Logging
    with I18nSupport {

  import ScheduleController.ScheduleForm

  val quartzExpressionConstraint: Constraint[String] =
    Constraint("quartz.expression") { expression =>
      Try(CronExpression.validateExpression(expression)) match {
        case Success(()) => Valid
        case Failure(pe: ParseException) =>
          Invalid(s"Invalid Quartz expression: ${pe.getMessage}")
        case Failure(_) => Invalid(s"Invalid Quartz expression")
      }
    }
  val timezoneConstraint: Constraint[String] = Constraint("timezone") {
    expression =>
      Try(TimeZone.getTimeZone(expression)) match {
        case Success(_) => Valid
        case Failure(_) => Invalid(s"Invalid timezone")
      }
  }
  val timeZones: List[String] = TimeZone.getAvailableIDs.toList.sorted

  val scheduleForm = Form[ScheduleForm](
    mapping(
      "id" -> uuid,
      "projectName" -> nonEmptyText,
      "stage" -> nonEmptyText,
      "schedule" -> nonEmptyText.verifying(quartzExpressionConstraint),
      "timezone" -> nonEmptyText.verifying(timezoneConstraint),
      "enabled" -> boolean
    )(ScheduleForm.apply)(ScheduleForm.unapply)
  )

  def list = authAction { implicit request =>
    val schedules = scheduleRepository.getScheduleList()
    Ok(views.html.schedule.list(config, menu)(request, schedules))
  }

  def form = authAction { implicit request =>
    Ok(
      views.html.schedule.form(config, menu)(
        scheduleForm.fill(
          ScheduleForm(UUID.randomUUID(), "", "", "", "", enabled = true)
        ),
        prismLookup,
        timeZones
      )
    )
  }

  def save = authAction { implicit request =>
    scheduleForm
      .bindFromRequest()
      .fold(
        formWithErrors =>
          Ok(
            views.html.schedule
              .form(config, menu)(formWithErrors, prismLookup, timeZones)
          ),
        form => {
          val config = form.toConfig(new DateTime(), request.user.fullName)
          scheduleRepository.setSchedule(config)
          deployScheduler.reschedule(config)
          Redirect(routes.ScheduleController.list)
        }
      )
  }

  def edit(id: String) = authAction { implicit request =>
    scheduleRepository
      .getSchedule(UUID.fromString(id))
      .fold(NotFound(s"Schedule with ID $id doesn't exist"))(scheduleConfig =>
        Ok(
          views.html.schedule.form(config, menu)(
            scheduleForm.fill(ScheduleForm(scheduleConfig)),
            prismLookup,
            timeZones
          )
        )
      )
  }

  def delete(id: String) = authAction { implicit request =>
    Form("action" -> nonEmptyText)
      .bindFromRequest()
      .fold(
        errors => {},
        { case "delete" =>
          val uuid = UUID.fromString(id)
          scheduleRepository.deleteSchedule(uuid)
          deployScheduler.cancel(uuid)
        }
      )
    Redirect(routes.ScheduleController.list)
  }

}

object ScheduleController {

  case class ScheduleForm(
      id: UUID,
      projectName: String,
      stage: String,
      schedule: String,
      timezone: String,
      enabled: Boolean
  ) {
    def toConfig(lastEdited: DateTime, user: String): ScheduleConfig =
      ScheduleConfig(
        id,
        projectName,
        stage,
        schedule,
        timezone,
        enabled,
        lastEdited,
        user
      )
  }
  object ScheduleForm {
    def apply(config: ScheduleConfig): ScheduleForm =
      ScheduleForm(
        config.id,
        config.projectName,
        config.stage,
        config.scheduleExpression,
        config.timezone,
        config.enabled
      )
  }

}
