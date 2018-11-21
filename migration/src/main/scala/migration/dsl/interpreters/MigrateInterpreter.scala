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

    case GetCursor(collection, skip, limit, formatter) =>
      Mongo.getCursor(collection, skip, limit, formatter)

    case GetCount(collection) =>
      Mongo.getCount(collection)
      
    case CreateTable(name, idName, idType) => 
      Postgre.createTable(name, idName, idType)
    
    case DropTable(name) => 
      Postgre.dropTable(name)
    
    case InsertAll(table, records, formatter) =>
      Postgre.insertAll(table, records, formatter)
  }
}