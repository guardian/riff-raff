package controllers

import java.net.URLEncoder
import java.util.UUID

import com.gu.googleauth.AuthAction
import forms.MigrationParameters
import play.api.i18n.I18nSupport
import play.api.mvc.{AnyContent, BaseController, ControllerComponents}
import utils.LogAndSquashBehaviour
import persistence.Persistence

class MigrationController(AuthAction: AuthAction[AnyContent], val controllerComponents: ControllerComponents) extends BaseController with Logging with I18nSupport with LogAndSquashBehaviour {

  def form = AuthAction { implicit request =>
    Ok(views.html.migration.form(MigrationParameters.form, Persistence.store.collectionStats))
  }

  def start = AuthAction { implicit request =>
    MigrationParameters.form.bindFromRequest().fold(
      errors => {
        // logger.info(s"Errors: ${errors.errors}")
      },
      form => {
        form.action match {
          case "preview" =>

            Redirect(routes.MigrationController.dryRun)

          case "migrate" =>

            Redirect(routes.MigrationController.run)

          case _ =>
            throw new RuntimeException("Unknown action")
        }
      }
    )
  }

  def run = AuthAction { implicit request => NotFound }

  def dryRun = AuthAction { implicit request => NotFound }
}