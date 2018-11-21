package migration
package data

import io.circe._
import io.circe.syntax._
import java.time.Instant
import java.util.UUID
import org.mongodb.scala.{ Document => MDocument, _ }
import org.scalacheck._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Prop.forAll
import scala.collection.JavaConverters._

object MongoSpec extends Properties("MongoDocument") with FromBsonInstances with MongoGenerators {

  property("ApiKey") = forAll { (x: ApiKey) =>
    val json: Json = (encodeLongs andThen add("_id", x.key))(x.asJson)
    val doc = encode(json)
    val result = FromMongo[ApiKey].parseMongo(doc)
    result.exists(_ == x)
  }

  property("Auth") = forAll { (x: Auth) =>
    val json = add("_id", x.email)(x.asJson)
    val doc = encode(json)
    val result = FromMongo[Auth].parseMongo(doc)
    result.exists(_ == x)
  }

  property("DeploymentSelector") = forAll { (x: DeploymentSelector) =>
    val json = add("_typeHint", x match { 
      case AllSelector => "persistence.AllSelector".asJson
      case _ => "persistence.DeploymentKeysSelectorDocument".asJson
    })(x.asJson)
    val doc = encode(json)
    val result = FromMongo[DeploymentSelector].parseMongo(doc)
    result.exists(_ == x)
  }

  property("Parameters") = forAll { (x: Parameters) =>
    val doc = encode(x.asJson)
    val result = FromMongo[Parameters].parseMongo(doc)
    result.exists(_ == x)
  }

  property("Deploy") = forAll { (x: Deploy) =>
    val json = add("_id", x.id)(x.asJson)
    val doc = encode(json)
    val result = FromMongo[Deploy].parseMongo(doc)
    if (!result.exists(_==x)) {
      println(x.asJson)
      println(doc)
      println(doc.getAs[MDocument]("parameters"))
      println((doc.getAs[MDocument]("parameters").flatMap(FromMongo[Parameters].parseMongo)))
      println(result)
    }
    result.exists(_ == x)
  }

  property("TaskDetail") = forAll { (x: TaskDetail) =>
    val doc = encode(x.asJson)
    val result = FromMongo[TaskDetail].parseMongo(doc)
    result.exists(_ == x)
  }

  property("ThrowableDetail") = forAll { (x: ThrowableDetail) =>
    val doc = encode(x.asJson)
    val result = FromMongo[ThrowableDetail].parseMongo(doc)
    result.exists(_ == x)
  }

  property("Document") = forAll { (x: Document) =>
    val doc = encode(x.asJson)
    val result = FromMongo[Document].parseMongo(doc)
    result.exists(_ == x)
  }

  property("Log") = forAll { (x: Log) =>
    val json   = add("deploy", x.deploy)(x.asJson)
    val doc = encode(json)
    val result = FromMongo[Log].parseMongo(doc)
    result.exists(_ == x)
  }


}

trait MongoGenerators {
  def add[A: Encoder](key: String, value: A)(json: Json): Json =
    json.asObject.map(obj => ((key -> value.asJson) +: obj).asJson).getOrElse(json)

  def encodeLongs: Json => Json = json =>
    json.asObject.map { obj => 
      val newValues = obj.values.map(v => 
        if (v.isNumber)
          v.asNumber.flatMap { n =>
            if (n.toInt.isEmpty)
              n.toLong.map(l => JsonObject("$numberLong" -> l.toString.asJson).asJson)
            else   
              Some(n.asJson)
          }.getOrElse(v)
        else if (v.isObject)
          encodeLongs(v.asJson)
        else
          v
      )
      JsonObject((obj.keys.toList zip newValues) :_*).asJson
    }.getOrElse(json)

  def encode(a: Json) = MDocument(a.noSpaces)

  implicit val arbString: Arbitrary[String] =
    Arbitrary(Gen.asciiPrintableStr)

  implicit val arbInstant: Arbitrary[Instant] =
    Arbitrary(arbitrary[Long] suchThat (_ >= 0) map (Instant.ofEpochMilli(_)))

  implicit val arbApiKey: Arbitrary[ApiKey] = 
    Arbitrary(arbitrary[(String, String, String, Instant, Option[Instant], Map[String, Long])] map ApiKey.tupled)

  implicit val arbAuth: Arbitrary[Auth] =
    Arbitrary(arbitrary[(String, String, Instant)] map Auth.tupled)

  implicit val arbDeploy: Arbitrary[Deploy] =
    Arbitrary(arbitrary[(UUID, Option[String], Instant, Parameters, RunState, Option[Boolean], Option[Int], Option[Int], Option[Instant], Option[Boolean])] map Deploy.tupled)

  implicit val arbParameters: Arbitrary[Parameters] =
    Arbitrary(arbitrary[(String, String, String, String, Map[String, String], DeploymentSelector)] map Parameters.tupled)

  implicit val arbRunState: Arbitrary[RunState] =
    Arbitrary(Gen.oneOf(
      RunState.NotRunning, 
      RunState.Completed, 
      RunState.Running, 
      RunState.ChildRunning, 
      RunState.Failed
    ))
    
  implicit val arbDeploymentSelector: Arbitrary[DeploymentSelector] =
    Arbitrary(Gen.oneOf(Gen.const(AllSelector), Gen.listOf(arbitrary[DeploymentKey]) map KeysSelector))

  implicit val arbDeploymentKey: Arbitrary[DeploymentKey] =
    Arbitrary(arbitrary[(String, String, String, String)] map DeploymentKey.tupled)

  implicit val arbLog: Arbitrary[Log] =
    Arbitrary(arbitrary[(UUID, UUID, Option[UUID], Document, Instant)] map Log.tupled)

  implicit val arbDocument: Arbitrary[Document] =
    Arbitrary(Gen.oneOf(
      Gen.listOf(arbitrary[TaskDetail]) map TaskListDocument,
      arbitrary[TaskDetail] map TaskRunDocument,
      arbitrary[String] map InfoDocument,
      arbitrary[String] map CommandOutputDocument,
      arbitrary[String] map CommandErrorDocument,
      arbitrary[String] map VerboseDocument,
      arbitrary[String] map WarningDocument,
      arbitrary[(String, ThrowableDetail)] map FailDocument.tupled,
      Gen.const(DeployDocument),
      Gen.const(FinishContextDocument),
      Gen.const(FailContextDocument)
    ))

  implicit val arbTaskDetail: Arbitrary[TaskDetail] =
    Arbitrary(arbitrary[(String, String, String)] map TaskDetail.tupled)

  implicit val arbThrowableDetail: Arbitrary[ThrowableDetail] =
    Arbitrary(arbitrary[(String, String, String, Option[ThrowableDetail])] map ThrowableDetail.tupled)

}