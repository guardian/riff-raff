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
final case class Queries(qs: List[PreviewResponse]) extends PreviewResponse

object PreviewInterpreter extends Migrator[PreviewResponse] {
  
  val WINDOW_SIZE = 1000
  
  def zero = Queries(Nil)
  def combine(r1: PreviewResponse, r2: PreviewResponse) = r1 match {
    case Queries(qs) => Queries(r2 :: qs)
    case x => Queries(x :: r2 :: Nil)
  }

  def deleteTable(pgTable: ToPostgres[_]) =
    IO.succeed(CreateTable(pgTable.delete.statement))

  def insertAll[A](pgTable: ToPostgres[A], records: List[A]) =
    IO.foreach(records){ record =>
      IO.succeed(pgTable.insert(pgTable.key(record), Json.stringify(pgTable.json(record))).statement)
    }.map(InsertValues("", _))
}