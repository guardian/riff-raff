package migration
package dsl
package interpreters

import migration.data._
import play.api.libs.json.Json
import scalaz.zio.{IO, Ref}

sealed abstract class PreviewResponse
final case class DropTable(sql: String) extends PreviewResponse
final case class CreateTable(sql: String) extends PreviewResponse
final case class InsertValues(prelude: String, values: List[String]) extends PreviewResponse

object PreviewInterpreter extends Migrator[PreviewResponse] {

  val WINDOW_SIZE = 1000

  def dropTable(pgTable: ToPostgres[_]) =
    IO.succeed(DropTable(pgTable.drop.statement))

  def createTable(pgTable: ToPostgres[_]) =
    IO.succeed(CreateTable(pgTable.create.statement))

  def insertAll[A](pgTable: ToPostgres[A], records: List[A]) =
    IO.foreach(records){ record =>
      IO.succeed(pgTable.insert(pgTable.key(record), Json.stringify(pgTable.json(record))).statement)
    }.map(InsertValues("", _))
}