package migration
package dsl
package interpreters

import migration.data._
import scalaz.zio.IO
import scalaz.zio.console._
import scalikejdbc._

object PreviewInterpreter extends Migrator {

  val WINDOW_SIZE = 1000

  def dropTable(name: String): IO[Nothing, Unit] =
    putStrLn(s"DROP TABLE IF EXISTS $name")

  def createTable(name: String, idName: String, idType: ColType): IO[Nothing, Unit] =
    putStrLn(s"CREATE TABLE $name ( $idName $idType PRIMARY KEY, content jsonb )")

  def insertAll[A](table: String, records: List[A])(implicit formatter: ToPostgres[A]): IO[Nothing, _] =
    putStrLn(s"INSERT INTO $table VALUES") *>
      IO.traverse(records){ rec =>
        indent *> putStrLn(s"( ${formatter.key(rec)}, ${formatter.json(rec)} )")
      }

}