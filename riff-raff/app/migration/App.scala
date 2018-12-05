package migration

import scalaz._, Scalaz._
import scalaz.zio.{ IO, App }
import scalaz.zio.interop.scalaz72._
import scalaz.zio.interop.console.scalaz._
import scalikejdbc._
import migration.data._
import migration.dsl._
import migration.dsl.interpreters._

object MigrationApp extends App with Args with Migrator {

  val WINDOW_SIZE: Int = 1000

  override def run(args: List[String]): IO[Nothing, ExitStatus] = 
    parseArgs(args) redeem (
      _ => usage.const(ExitStatus.ExitNow(1)), 
      cfg => IO.bracket(
        Mongo.connect(cfg.mongoUri) <* Postgres.connect(cfg.pgUri, cfg.pgUser, cfg.pgPassword)          
      ) { case (mongoClient, _) => 
        Mongo.disconnect(mongoClient) *> Postgres.disconnect
      } { case (_, mongoDb) => 
        IO.traverse(cfg.tables) { mongoTable =>
          val program = mongoTable match {
            case "apiKeys"      => 
              Some(migrate(cfg, mongoDb, mongoTable, PgTable[ApiKey]("apiKey", "id", ColString(32, false))))
            case "auth"         => 
              Some(migrate(cfg, mongoDb, mongoTable, PgTable[Auth]("auth", "email", ColString(100, true))))
            case "deployV2"     => 
              Some(migrate(cfg, mongoDb, mongoTable, PgTable[Deploy]("deploy", "id", ColUUID)))
            case "deployV2Logs" => 
              Some(migrate(cfg, mongoDb, mongoTable, PgTable[Log]("deployLog", "id", ColUUID)))
            case _ =>
              None
          }

          program.fold[IO[MigrationError, Unit]](
            IO.fail(MissingTable(mongoTable))
          )(_.foldMap(if (cfg.dryRun) PreviewInterpreter else MigrateInterpreter))
        }
      }
    ) redeem (
      e => catastrophe(e).const(ExitStatus.ExitNow(1)),
      _ => IO.now(ExitStatus.ExitNow(0))
    )
}

