package migration
package dsl
package interpreters

import migration.data._
import scalaz.~>
import scalaz.zio.IO
import scalikejdbc._

object MigrateInterpreter extends (MigrationF ~> IO[MigrationError, ?]) {
  def apply[A](op: MigrationF[A]): IO[MigrationError, A] = op match {

    case GetCollection(mongo, name) =>
      Mongo.getCollection(mongo, name)

    case GetCursor(collection) =>
      Mongo.getCursor(collection)

    case GetItems(cursor, limit, formatter) =>
      Mongo.getItems(cursor, limit, formatter)

    case GetCount(collection) =>
      Mongo.getCount(collection)
      
    case CreateTable(name, idName, idType) => 
      Postgres.createTable(name, idName, idType)
    
    case DropTable(name) => 
      Postgres.dropTable(name)
    
    case InsertAll(table, records, formatter) =>
      Postgres.insertAll(table, records, formatter)
  }
}