package controllers

import java.net.URLEncoder
import java.util.UUID

import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import cats.syntax.either._
import ci.{Builds, S3Tag, TagClassification}
import com.amazonaws.services.s3.model.GetObjectRequest
import com.gu.googleauth.AuthAction
import conf.Configuration
import controllers.forms.{DeployParameterForm, UuidForm}
import deployment._
import magenta._
import magenta.artifact._
import magenta.deployment_type.DeploymentType
import magenta.input.{All, DeploymentKey, DeploymentKeysSelector}
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import persistence.RestrictionConfigDynamoRepository
import play.api.http.HttpEntity
import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc.{AnyContent, BaseController, ControllerComponents}
import resources.PrismLookup
import restrictions.RestrictionChecker
import utils.LogAndSquashBehaviour

import scala.util.{Failure, Success}

class MigrationController(AuthAction: AuthAction[AnyContent], val controllerComponents: ControllerComponents)
  (implicit val wsClient: WSClient) extends BaseController with Logging with I18nSupport with LogAndSquashBehaviour {

  def form = AuthAction { implicit request =>
    NotFound
    // DeployParameterForm.form.bindFromRequest().fold(
    //   errors => {
    //     logger.info(s"Errors: ${errors.errors}")
    //     BadRequest(views.html.deploy.form(errors, prismLookup))
    //   },
    //   form => {
    //     val parameters = new DeployParameters(Deployer(request.user.fullName),
    //       Build(form.project, form.build.toString),
    //       Stage(form.stage),
    //       selector = form.makeSelector
    //     )

    //     form.action match {
    //       case "preview" =>
    //         val maybeKeys = parameters.selector match {
    //           case All => None
    //           case DeploymentKeysSelector(keys) => Some(DeploymentKey.asString(keys))
    //         }
    //         Redirect(routes.PreviewController.preview(
    //           parameters.build.projectName, parameters.build.id, parameters.stage.name, maybeKeys)
    //         )
    //       case "deploy" =>
    //         val uuid = deployments.deploy(parameters, requestSource = UserRequestSource(request.user)).valueOr{ error =>
    //           throw new IllegalStateException(error.message)
    //         }
    //         Redirect(routes.DeployController.viewUUID(uuid.toString))
    //       case _ => throw new RuntimeException("Unknown action")
    //     }
    //   }
    // )
  }

}