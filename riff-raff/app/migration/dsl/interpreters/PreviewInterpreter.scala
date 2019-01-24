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

  def dropTable(name: String) =
    IO.now(DropTable(s"DROP TABLE IF EXISTS $name"))

  def createTable(name: String, idName: String, idType: ColType) =
    IO.now(CreateTable(s"CREATE TABLE $name ( $idName $idType PRIMARY KEY, content jsonb )"))

  def insertAll[A](table: String, records: List[A])(implicit formatter: ToPostgres[A]) =
    IO.traverse(records){ rec =>
      IO.now(s"( ${formatter.key(rec)}, ${Printer.noSpaces.pretty(formatter.json(rec))} )")
    }.map(InsertValues(s"INSERT INTO $table VALUES", _))

}