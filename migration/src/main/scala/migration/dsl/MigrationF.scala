package migration
package dsl

import migration.data.{ToPostgre, FromMongo}
import org.mongodb.scala._

sealed abstract class ColType { self =>
  override def toString = self match {
    case ColString(len, true)  => s"varchar($len)"
    case ColString(len, false) => s"char($len)"
    case ColUUID               => "uuid"
  }
}

final case class ColString(len: Int, varlen: Boolean) extends ColType
case object ColUUID extends ColType

sealed abstract class MigrationF[A]
final case class GetCollection(mongo: MongoDatabase, name: String) extends MigrationF[MongoCollection[Document]]
final case class GetCount(collection: MongoCollection[Document]) extends MigrationF[Long]
final case class GetCursor[A](collection: MongoCollection[Document], skip: Int, limit: Int, formatter: FromMongo[A]) extends MigrationF[List[A]]
final case class DropTable(name: String) extends MigrationF[Unit]
final case class CreateTable(name: String, id: String, idType: ColType) extends MigrationF[Unit]
final case class InsertAll[A](table: String, records: List[A], formatter: ToPostgre[A]) extends MigrationF[Unit]
