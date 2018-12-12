package migration

import migration.data._
import migration.dsl.interpreters._
import controllers.Logging
import controllers.forms.MigrationParameters
import lifecycle.Lifecycle
import scalaz.zio._
import scalaz.zio.internal.Executor
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
          case "apiKeys"      => 
            MigrateInterpreter.migrate(mongoDb, mongoTable, PgTable[ApiKey]("apiKey", "id", ColString(32, false))) *> 
              IO.sync { status += mongoTable -> 100 }
          case "auth"         => 
            MigrateInterpreter.migrate(mongoDb, mongoTable, PgTable[Auth]("auth", "email", ColString(100, true))) *> 
              IO.sync { status += mongoTable -> 100 }
          case "deployV2"     => 
            MigrateInterpreter.migrate(mongoDb, mongoTable, PgTable[Deploy]("deploy", "id", ColUUID)) *> 
              IO.sync { status += mongoTable -> 100 }
          case "deployV2Logs" => 
            MigrateInterpreter.migrate(mongoDb, mongoTable, PgTable[Log]("deployLog", "id", ColUUID)) *> 
              IO.sync { status += mongoTable -> 100 }
          case _ =>
            IO.fail(MissingTable(mongoTable))
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

}