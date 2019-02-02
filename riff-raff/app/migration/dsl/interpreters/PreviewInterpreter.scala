package migration
package dsl
package interpreters

import io.circe.Printer
import migration.data._
import scalaz.zio.{IO, Ref}

sealed abstract class PreviewResponse
final case class DropTable(sql: String) extends PreviewResponse
final case class CreateTable(sql: String) extends PreviewResponse
final case class InsertValues(prelude: String, values: List[String]) extends PreviewResponse

object PreviewInterpreter extends Migrator[PreviewResponse] {

  val WINDOW_SIZE = 1000

  def dropTable(pgTable: ToPostgres[_]) =
    IO.now(DropTable(pgTable.drop.statement))

  def createTable(pgTable: ToPostgres[_]) =
    IO.now(CreateTable(pgTable.create.statement))

  def insertAll[A](pgTable: ToPostgres[A], records: List[A]) =
    IO.traverse(records){ record =>
      IO.now(pgTable.insert(pgTable.key(record), pgTable.json(record).noSpaces).statement)
    }.map(InsertValues("", _))
}