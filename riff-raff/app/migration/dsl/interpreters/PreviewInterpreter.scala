package migration
package dsl
package interpreters

import migration.data._
import scalaz._, Scalaz._
import scalaz.zio.IO
import scalaz.zio.interop.console.scalaz._
import scalaz.zio.interop.scalaz72._
import scalikejdbc._

object PreviewInterpreter extends (MigrationF ~> IO[MigrationError, ?]) {
  def apply[A](op: MigrationF[A]): IO[MigrationError, A] = op match {

    case GetCollection(mongo, name) =>
      Mongo.getCollection(mongo, name)

    case GetCount(collection) =>
      Mongo.getCount(collection)
      
    case GetCursor(collection) =>
      Mongo.getCursor(collection)

    case GetItems(cursor, limit, formatter) =>
      Mongo.getItems(cursor, limit, formatter)

    case InsertAll(table, records, formatter) =>
      putStrLn(s"INSERT INTO $table VALUES").leftMap(ConsoleError(_)) *>
        IO.traverse(records){ rec =>
          indent *> putStrLn(s"( ${formatter.key(rec)}, ${formatter.json(rec)} )")
        }.leftMap(ConsoleError(_)).void

    case CreateTable(name, idName, idType) =>
      putStrLn(s"CREATE TABLE $name ( $idName $idType PRIMARY KEY, content jsonb )").leftMap(ConsoleError(_))
    
    case DropTable(name) =>
      putStrLn(s"DROP TABLE IF EXISTS $name").leftMap(ConsoleError(_))
    
  }
}