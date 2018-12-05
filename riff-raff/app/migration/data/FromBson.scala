package migration
package data

import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import org.bson.UuidRepresentation
import org.mongodb.scala.{ Document => MDocument, _ }
import org.mongodb.scala.bson.BsonValue
import scala.collection.JavaConverters._
import scala.util.Try
import scalaz._, Scalaz._

trait FromBson[A] {
  def getAs(b: BsonValue): Option[A]
}

object FromBson {
  def apply[A](implicit F: FromBson[A]): FromBson[A] = F
}

trait FromBsonInstances {
  implicit val stringBson: FromBson[String] = (b: BsonValue) =>
    Try(b.asString.getValue).toOption
  
  implicit val longBson: FromBson[Long] = (b: BsonValue) =>
    Try(b.asInt32.getValue).map(_.toLong).toOption orElse Try(b.asInt64.getValue).toOption
  
  implicit val intBson: FromBson[Int] = (b: BsonValue) =>
    Try(b.asInt32.getValue).toOption
  
  implicit val dateTimeBson: FromBson[Instant] = (b: BsonValue) =>
    Try(b.asString.getValue).map(Instant.parse(_)).toOption
  
  implicit val uuidBson: FromBson[UUID] = (b: BsonValue) =>
    Try(b.asString.getValue).map(UUID.fromString(_)).toOption orElse
      Try(b.asBinary.asUuid(UuidRepresentation.JAVA_LEGACY)).toOption orElse // uses type 3 encoding
      Try(b.asBinary.asUuid(UuidRepresentation.STANDARD)).toOption

  implicit val booleanBson: FromBson[Boolean] = (b: BsonValue) =>
    Try(b.asBoolean.getValue).toOption

  implicit def mapBson[A](implicit A: FromBson[A]): FromBson[Map[String, A]] = (b: BsonValue) =>
    Try(b.asDocument.asScala.toMap).toOption.flatMap(_.traverse(A.getAs))
  
  implicit val documentBson: FromBson[MDocument] = (b: BsonValue) =>
    Try(b.asDocument.asScala.toMap.toList).map(MDocument(_)).toOption

  implicit val runstateBson: FromBson[RunState] = (b: BsonValue) =>
    stringBson.getAs(b).flatMap {
      case "NotRunning" => Some(RunState.NotRunning)
      case "Completed" => Some(RunState.Completed)
      case "Running" => Some(RunState.Running)
      case "ChildRunning" => Some(RunState.ChildRunning)
      case "Failed"  => Some(RunState.Failed)
      case _ => None
    }

  implicit def option[A: FromBson]: FromBson[Option[A]] = new FromBson[Option[A]] {
    def getAs(b: BsonValue): Option[Option[A]] =
      if (b.isNull)
        Some(None)
      else
        FromBson[A].getAs(b) match {
          case Some(a) => Some(Some(a))
          case None => Some(None)
        }
  }

  implicit def list[A: FromBson]: FromBson[List[A]] = new FromBson[List[A]] {
    def getAs(b: BsonValue): Option[List[A]] =
      Try(b.asArray.getValues.asScala.toList).toOption.flatMap(_.traverse(FromBson[A].getAs))
  }

}