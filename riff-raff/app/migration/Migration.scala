package migration

import migration.data._
import migration.dsl.interpreters._
import controllers.{ ApiKey, AuthorisationRecord, Logging }
import controllers.forms.MigrationParameters
import lifecycle.Lifecycle
import persistence.{MongoFormat, LogDocument, Persistence, DeployRecordDocument}
import scalaz.zio._
import scalaz.zio.internal.Executor
import scalaz.zio.duration._
import scalaz.zio.console._
import scala.concurrent.{Future, Promise}

class Migration() extends Lifecycle with Logging {

  var rts: RTS = null

  val status: collection.mutable.Map[String, Double] = collection.mutable.Map.empty

  def init() {
    rts = new RTS {}
  }

  def shutdown() {
    rts.env.executor(Executor.Unyielding).shutdown()
    rts.env.executor(Executor.Yielding).shutdown()
    rts.env.scheduler.shutdown()
  }

  def migrate(settings: MigrationParameters): Future[Unit] = {
    val ioprogram = IO.bracket(
      Postgres.connect(
        conf.Config.postgres.url, 
        conf.Config.postgres.user, 
        conf.Config.postgres.password
      )
    ) { _ => 
      Postgres.disconnect
    } { _ => 
      run("apiKeys", MongoRetriever.apiKeyRetriever, settings.limit) *>
        run("auth", MongoRetriever.authRetriever, settings.limit) *>
        run("deployV2", MongoRetriever.deployRetriever, settings.limit) *>
        run("deployV2Logs", MongoRetriever.logRetriever, settings.limit)
    }

    val promise = Promise[Unit]()

    rts.unsafeRunAsync(ioprogram) {
      case ExitResult.Succeeded(_) => promise.success(())
      case ExitResult.Failed(t) => promise.failure(new FiberFailure(t))
    }

    promise.future
  }

  def run[A: MongoFormat: ToPostgres](mongoTable: String, retriever: MongoRetriever[A], limit: Option[Int]) =
    for {
      _ <- putStrLn(s"Migrating $mongoTable...")
      vals <- MigrateInterpreter.migrate(retriever, limit)
      (counter, reader, writer, _, _) = vals
      progress <- monitor(mongoTable, counter).fork
      _ <- Fiber.joinAll(reader :: writer :: Nil)
      _ <- progress.interrupt
      _ <- putStrLn(s"Done!")
    } yield ()

  def monitor(mongoTable: String, counter: Ref[Int]): IO[Nothing, Unit] =
    (counter.get.flatMap { n => IO.sync { status += mongoTable -> 100 / n } } *> IO.sleep(Migration.interval))
      .forever
      .ensuring(IO.sync(status.foreach { case (k, v) => status += k -> 100 }))

}

object Migration {

  val interval: Duration = Duration(200L, java.util.concurrent.TimeUnit.MILLISECONDS)

}