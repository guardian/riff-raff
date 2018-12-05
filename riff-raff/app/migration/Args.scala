package migration

import migration.data._
import scala.util.Try
import scalaz._, Scalaz._
import scalaz.zio.IO
import scalaz.zio.interop.scalaz72._
import scalaz.zio.interop.console.scalaz._

trait Args {
  def parseArgs(args: List[String]): IO[MigrationError, Config] = {
    def go(args: List[String], cfg: Config): IO[InvalidArgs, Config] = args match {
      case Nil => IO.now(cfg)
      case "-t" :: ts :: rest =>
        go(rest, cfg.copy(tables = cfg.tables ++ ts.split(',').toList))
      case "-n" :: n :: rest =>
        Try(n.toInt).fold(_ => go(rest, cfg), n => go(rest, cfg.copy(limit = Some(n))))
      case "-d" :: rest =>
        go(rest, cfg.copy(dryRun = true))
      case mongoUri :: pgUri :: pgUser :: pgPassword :: Nil =>
        IO.now(cfg.copy(mongoUri = mongoUri, pgUri = pgUri, pgUser = pgUser, pgPassword = pgPassword))
      case xs =>
        IO.fail(InvalidArgs(xs))
    }
    go(args, Config("", "", "", "", Nil, None, false)).flatMap {
      case Config("", _, _, _, _, _, _) => IO.fail(MissingArgs)
      case x => IO.now(x)
    }
  }

  def usage: IO[Nothing, Unit] = IO.traverse(List(
    putStrLn("Usage:"),
    indent, putStrLn("migrate [options] <mongodb-uri> <pgdb-uri> <pgdb-user> <pgdb-password>"),
    lineFeed,
    indent, putStrLn("options:"),
    indent, indent, putStrLn("-t <table>,...,<table>  List of tables to migrate"),
    indent, indent, putStrLn("-n <int>                Max number of rows to migrate"),
    indent, indent, putStrLn("-d                      Dry run, does not actually write anything"),
    lineFeed
  ))(identity _).void.catchAll {
    case t => IO.unit // this won't happen
  }

  def catastrophe(e: MigrationError): IO[Nothing, Unit] = IO.unit
}