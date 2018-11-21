package migration
package data

import io.circe._
import io.circe.parser._
import io.circe.syntax._
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import org.bson._
import org.mongodb.scala.{ Document => MDocument, _ }
import org.scalacheck._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Prop.forAll
import scala.collection.JavaConverters._

object BsonSpec extends Properties("BsonValue") with FromBsonInstances with BsonGenerators {

  property("int") = forAll { (x: Int) =>
    val bson = new BsonInt32(x)
    val result = FromBson[Int].getAs(bson)
    result.exists(_ == x)
  }

  property("long") = forAll { (x: Long) =>
    val bson = new BsonInt64(x)
    val result = FromBson[Long].getAs(bson)
    result.exists(_ == x)
  }

  property("string") = forAll { (x: String) =>
    val bson = new BsonString(x)
    val result = FromBson[String].getAs(bson)
    result.exists(_ == x)
  }

  property("instant") = forAll { (x: Instant) =>
    val bson = new BsonString(x.toString)
    val result = FromBson[Instant].getAs(bson)
    result.exists(_ == x)
  }  

  property("bool") = forAll { (x: Boolean) =>
    val bson = new BsonBoolean(x)
    val result = FromBson[Boolean].getAs(bson)
    result.exists(_ == x)
  }

  property("map of strings") = forAll(objectOfStrings) { (x: Map[String, String]) =>
    val bson = x.foldLeft(new BsonDocument)((bson, kv) => bson append (kv._1, new BsonString(kv._2)))
    val result = FromBson[Map[String, String]].getAs(bson)
    result.exists(_ == x)
  }

  property("map of longs") = forAll(objectOfLongs) { (x: Map[String, Long]) =>
    val bson = x.foldLeft(new BsonDocument)((bson, kv) => bson append (kv._1, new BsonInt64(kv._2)))
    val result = FromBson[Map[String, Long]].getAs(bson)
    result.exists(_ == x)
  }

  property("runstate") = forAll { (x: RunState) => 
    val bson = new BsonString(x.toString)
    val result = FromBson[RunState].getAs(bson)
    result.exists(_ == x)
  }

  property("list of strings") = forAll { (x: List[String]) =>
    val bson = new BsonArray(x.map(new BsonString(_)).asJava)
    val result = FromBson[List[String]].getAs(bson)
    result.exists(_ == x)
  }

  property("documents") = forAll { (x: JsonObject) =>
    val json = Json.fromJsonObject(x)
    val bson = BsonDocument.parse(json.noSpaces)
    val result = FromBson[MDocument].getAs(bson).map(_.toJson).flatMap(parse(_).toOption)
    result.exists(_ == json)
  }

  property("uuid") = forAll { (x: UUID) =>
    val buf: ByteBuffer = ByteBuffer.wrap(Array.ofDim[Byte](16))
    buf.putLong(x.getMostSignificantBits)
    buf.putLong(x.getLeastSignificantBits)
    val bson = new BsonBinary(BsonBinarySubType.UUID_STANDARD, buf.array)
    val result = FromBson[UUID].getAs(bson)
    result.exists(_ == x)
  }

}

trait BsonGenerators {
  val objectOfStrings = Gen.mapOf(for {
    k <- Gen.asciiPrintableStr
    v <- Gen.asciiPrintableStr
  } yield (k, v))

  val objectOfLongs = Gen.mapOf(for {
    k <- Gen.asciiPrintableStr
    v <- arbitrary[Long]
  } yield (k, v))

  implicit val arbJsonObject: Arbitrary[JsonObject] = Arbitrary(for {
    keys <- Gen.listOf(Gen.asciiPrintableStr)
    values <- Gen.listOfN(keys.size, Gen.oneOf(
      Gen.asciiPrintableStr map (_.asJson), 
      arbitrary[Int] map (_.asJson), 
      arbitrary[Long] map (l => JsonObject("$numberLong" -> l.toString.asJson).asJson), 
      Gen.const(Json.True),
      Gen.const(Json.False),
      Gen.const(Json.Null)
    ))
  } yield JsonObject((keys zip values) :_*))

  implicit val arbInstant: Arbitrary[Instant] = Arbitrary(arbitrary[Long] suchThat (_ >= 0) map (Instant.ofEpochMilli(_)))
  implicit val runState: Arbitrary[RunState] = Arbitrary(Gen.oneOf(
    RunState.NotRunning, 
    RunState.Completed, 
    RunState.Running, 
    RunState.ChildRunning, 
    RunState.Failed
  ))
}