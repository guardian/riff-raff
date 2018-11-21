import scalaz.Free

import migration.data.{ Document => _, _ }
import migration.dsl._
import java.io.IOException
import org.mongodb.scala._
import scalaz._, Scalaz._
import scalaz.zio.{ IO, App }
import scalaz.zio.interop.scalaz72._
import scalaz.zio.interop.console.scalaz._

package object migration {
  type Migration[E, A] = FreeT[MigrationF, IO[E, ?], A]

  val lineFeed = putStrLn("")
  val indent = putStr("  ")

  def getCollection(db: MongoDatabase, name: String): Migration[MigrationError, MongoCollection[Document]] =
    FreeT.liftF(GetCollection(db, name))

  def getCount(coll: MongoCollection[Document]): Migration[MigrationError, Long] =
    FreeT.liftF(GetCount(coll))

  def getCursor[A](coll: MongoCollection[Document], skip: Int, limit: Int)(implicit F: FromMongo[A]): Migration[MigrationError, List[A]] =
    FreeT.liftF[MigrationF, IO[MigrationError, ?], List[A]](GetCursor(coll, skip, limit, F))

  def dropTable(name: String): Migration[MigrationError, Unit] = 
    FreeT.liftF(DropTable(name))

  def createTable(name: String, id: String, idType: ColType): Migration[MigrationError, Unit] = 
    FreeT.liftF(CreateTable(name, id, idType))
    
  def insertAll[A](table: String, records: List[A])(implicit T: ToPostgre[A]): Migration[MigrationError, Unit] = 
    FreeT.liftF[MigrationF, IO[MigrationError, ?], Unit](InsertAll(table, records, T))
}