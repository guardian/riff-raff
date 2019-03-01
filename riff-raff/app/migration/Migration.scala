package migration

import conf.Config
import migration.data._
import migration.dsl.interpreters._
import controllers.{ ApiKey, AuthorisationRecord, Logging }
import controllers.forms.MigrationParameters
import lifecycle.Lifecycle
import persistence.{DataStore, MongoFormat, LogDocument, DeployRecordDocument}
import scalaz.zio._
import scalaz.zio.internal.Executor
import scalaz.zio.duration._
import scala.concurrent.{Future, Promise}

class Migration(config: Config, datastore: DataStore) extends Lifecycle with Logging {

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
        config.postgres.url, 
        config.postgres.user, 
        config.postgres.password
      )
    ) { _ => 
      Postgres.disconnect
    } { _ => 
      // run("apiKeys", MongoRetriever.apiKeyRetriever(datastore), settings.limit) *>
      //   run("auth", MongoRetriever.authRetriever(datastore), settings.limit) *>
        run("deployV2", MongoRetriever.deployRetriever(datastore), settings.limit)
        // run("deployV2Logs", MongoRetriever.logRetriever(datastore), settings.limit)
    }

    val promise = Promise[Unit]()

    rts.unsafeRunAsync(ioprogram) {
      case Exit.Success(_) => promise.success(())
      case Exit.Failure(t) => promise.failure(new FiberFailure(t))
    }

    promise.future
  }

  def run[A: MongoFormat: ToPostgres](mongoTable: String, retriever: MongoRetriever[A], limit: Option[Int]) =
    (for {
      _ <- IO.sync(log.info(s"Migrating $mongoTable..."))
      vals <- MigrateInterpreter.migrate(retriever, limit)
      (counter, reader, writer, _) = vals
      progress <- monitor(mongoTable, counter).fork
      _ <- Fiber.joinAll(reader :: writer :: Nil)
      _ <- progress.interrupt
      _ <- IO.sync(log.info(s"Done!"))
    } yield ()).catchAll {
      case MissingTable(t)  => IO.sync(log.warn(s"Table $t does not exist bro"))
      case DatabaseError(t) => IO.sync(log.error(s"Hmm, something went wrong while migrating $mongoTable", t))
    }

  def monitor(mongoTable: String, counter: Ref[Int]): IO[Nothing, Unit] =
    (counter.get.flatMap { n => IO.sync { status += mongoTable -> 100 / Math.max(n, 1) } } *> IO.sleep(Migration.interval))
      .forever
      .ensuring(IO.sync(status.foreach { case (k, v) => status += k -> 100 }))

}

object Migration {

  val interval: Duration = Duration(200L, java.util.concurrent.TimeUnit.MILLISECONDS)

}