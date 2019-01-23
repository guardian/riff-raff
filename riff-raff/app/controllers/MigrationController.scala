package controllers

import java.net.URLEncoder
import java.util.UUID

import com.gu.googleauth.AuthAction
import forms.MigrationParameters
import play.api.i18n.I18nSupport
import play.api.mvc.{AnyContent, BaseController, ControllerComponents, Result}
import utils.LogAndSquashBehaviour
import persistence.{DeployRecordDocument, LogDocument, Persistence, MongoFormat}
import migration._
import migration.data._
import migration.dsl._
import migration.dsl.interpreters._
import scalaz.zio.{ Fiber, IO, RTS, ExitResult, Ref, FiberFailure }
import scala.concurrent.Promise

class MigrationController(
  AuthAction: AuthAction[AnyContent],
  val migrations: Migration,
  val controllerComponents: ControllerComponents
) extends BaseController with Logging with I18nSupport with LogAndSquashBehaviour {

  val WINDOW_SIZE = 1000

  def form = AuthAction { implicit request =>
    Ok(views.html.migrations.form(MigrationParameters.form, Persistence.store.collectionStats))
  }

  def start = AuthAction { implicit request =>
    MigrationParameters.form.bindFromRequest().fold(
      errors => {
        log.info(s"Errors: ${errors.errors}")
        BadRequest(views.html.migrations.form(errors, Persistence.store.collectionStats))
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
    ???
  }

  def status = AuthAction { implicit request =>
    Ok(views.html.migrations.log(migrations.status.toMap))
  }

  def dryRun(settings: MigrationParameters) = AuthAction.async { implicit request => 
    val rts = new RTS {}

    val ioprogram = 
      IO.traverse(List("apiKeys", "auth", "deployV2", "deployV2Logs")) { mongoTable =>
        mongoTable match {
          case "apiKeys"      => run(mongoTable, MongoRetriever.apiKeyRetriever, PgTable[ApiKey]("apiKey", "id", ColString(32, false)), settings.limit)
          case "auth"         => run(mongoTable, MongoRetriever.authRetriever, PgTable[AuthorisationRecord]("auth", "email", ColString(100, true)), settings.limit)
          case "deployV2"     => run(mongoTable, MongoRetriever.deployRetriever, PgTable[DeployRecordDocument]("deploy", "id", ColUUID), settings.limit)
          case "deployV2Logs" => run(mongoTable, MongoRetriever.logRetriever, PgTable[LogDocument]("deployLog", "id", ColUUID), settings.limit)
          case _ =>
            IO.fail(MissingTable(mongoTable))
        }
      }

    val promise = Promise[Result]()

    rts.unsafeRunAsync(ioprogram) {
      case ExitResult.Succeeded(_) => promise.success(Redirect(routes.MigrationController.form))
      case ExitResult.Failed(t) => promise.success(InternalServerError(views.html.errorContent(new FiberFailure(t), "Migration failed")))
    }

    promise.future
  }

  def run[A: MongoFormat: ToPostgres](mongoTable: String, retriever: MongoRetriever[A], pgTable: PgTable[A], limit: Option[Int]) =
    for {
      vals <- PreviewInterpreter.migrate(retriever, pgTable, limit)
      (_, reader, writer) = vals
      _ <- reader.join
      _ <- writer.interrupt
    } yield ()
}
