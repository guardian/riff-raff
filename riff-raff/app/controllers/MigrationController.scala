package controllers

import java.net.URLEncoder
import java.util.UUID

import conf.Config
import com.gu.googleauth.AuthAction
import forms.MigrationParameters
import play.api.i18n.I18nSupport
import play.api.mvc.{AnyContent, BaseController, ControllerComponents, Result}
import utils.LogAndSquashBehaviour
import persistence.{DataStore, DeployRecordDocument, LogDocument, MongoFormat}
import migration._
import migration.data._
import migration.dsl._
import migration.dsl.interpreters._
import scalaz.zio.{ Fiber, IO, RTS, Exit, Ref, FiberFailure }
import scala.concurrent.Promise

class MigrationController(
  AuthAction: AuthAction[AnyContent],
  val migrations: Migration,
  val controllerComponents: ControllerComponents,
  val datastore: DataStore,
  val menu: Menu,
  val config: Config
) extends BaseController with Logging with I18nSupport with LogAndSquashBehaviour {

  def form = AuthAction { implicit request =>
    Ok(views.html.migrations.form(MigrationParameters.form, datastore.collectionStats)(config, menu))
  }

  def start = AuthAction { implicit request =>
    MigrationParameters.form.bindFromRequest().fold(
      errors => {
        log.info(s"Errors: ${errors.errors}")
        BadRequest(views.html.migrations.form(errors, datastore.collectionStats)(config, menu))
      },
      form => {
        form.action match {
          case "preview" =>
            Redirect(routes.MigrationController.dryRun(form))

          case "migrate" =>
            migrations.migrate(form)
            Redirect(routes.MigrationController.view)

          case _ =>
            throw new RuntimeException("Unknown action")
        }
      }
    )
  }

  def view = AuthAction { implicit request =>
    Ok(views.html.migrations.view(config, menu))
  }

  def status = AuthAction { implicit request =>
    Ok(views.html.migrations.log(migrations.status.toMap))
  }

  def dryRun(settings: MigrationParameters) = AuthAction.async { implicit request => 
    val rts = new RTS {}

    val ioprogram: IO[MigrationError, List[PreviewResponse]] = 
      IO.foreach(List("deployV2", "deployV2Logs")) { mongoTable =>
        mongoTable match {
          // case "apiKeys"      => run(mongoTable, MongoRetriever.apiKeyRetriever(datastore), settings.limit)
          // case "auth"         => run(mongoTable, MongoRetriever.authRetriever(datastore), settings.limit)
          case "deployV2"     => run(mongoTable, MongoRetriever.deployRetriever(datastore), settings.limit)
          case "deployV2Logs" => run(mongoTable, MongoRetriever.logRetriever(datastore), settings.limit)
          case _ =>
            IO.fail(MissingTable(mongoTable))
        }
      }

    val promise = Promise[Result]()

    rts.unsafeRunAsync(ioprogram) {
      case Exit.Success(responses) => promise.success(Ok(views.html.migrations.preview(responses)(config, menu)))
      case Exit.Failure(t) => promise.success(InternalServerError(views.html.errorPage(config)(new FiberFailure(t))))
    }

    promise.future
  }

  def run[A: MongoFormat: ToPostgres](
    mongoTable: String, 
    retriever: MongoRetriever[A], 
    limit: Option[Int]
  ): IO[MigrationError, PreviewResponse] =
    for {
      vals <- PreviewInterpreter.migrate(retriever, limit)
      (_, reader, writer, r1) = vals
      _ <- reader.join
      responses <- writer.join
    } yield PreviewInterpreter.combine(responses, r1)
}
