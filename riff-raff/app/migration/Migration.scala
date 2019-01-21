package migration

import migration.data._
import migration.dsl.interpreters._
import controllers.Logging
import controllers.forms.MigrationParameters
import lifecycle.Lifecycle
import org.mongodb.scala.MongoDatabase
import scalaz.zio._
import scalaz.zio.internal.Executor
import scalaz.zio.duration._
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
      Mongo.connect(conf.Configuration.mongo.uri.get) <* Postgres.connect(
        conf.Configuration.postgres.url.get, 
        conf.Configuration.postgres.user.get, 
        conf.Configuration.postgres.password.get
      )
    ) { case (mongoClient, _) => 
      Mongo.disconnect(mongoClient) *> Postgres.disconnect
    } { case (_, mongoDb) => 
      IO.traverse(settings.collections) { mongoTable =>
        mongoTable match {
          case "apiKeys"      => run(mongoDb, mongoTable, PgTable[ApiKey]("apiKey", "id", ColString(32, false)))
            
          case "auth"         => run(mongoDb, mongoTable, PgTable[Auth]("auth", "email", ColString(100, true)))
            
          case "deployV2"     => run(mongoDb, mongoTable, PgTable[Deploy]("deploy", "id", ColUUID))

          case "deployV2Logs" => run(mongoDb, mongoTable, PgTable[Log]("deployLog", "id", ColUUID))

          case _              => IO.fail(MissingTable(mongoTable))
        }
      }
    }

    val promise = Promise[Unit]()

    rts.unsafeRunAsync(ioprogram) {
      case ExitResult.Succeeded(_) => promise.success(())
      case ExitResult.Failed(t) => promise.failure(new FiberFailure(t))
    }

    promise.future
  }

  def run[A: FromMongo: ToPostgres](mongoDb: MongoDatabase, mongoTable: String, pgTable: PgTable[A]) =
    for {
      vals <- MigrateInterpreter.migrate(mongoDb, mongoTable, pgTable)
      (counter, reader, writer) = vals
      progress <- monitor(mongoTable, counter).fork
      _ <- Fiber.joinAll(reader :: writer :: Nil)
      _ <- progress.interrupt
    } yield ()

  def monitor(mongoTable: String, counter: Ref[Long]): IO[Nothing, Unit] =
    (counter.get.flatMap { n => IO.sync { status += mongoTable -> 100 / n } } *> IO.sleep(Migration.interval)).forever

}

object Migration {

  val interval: Duration = Duration(1L, java.util.concurrent.TimeUnit.SECONDS)

}