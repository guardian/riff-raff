package magenta.deployment_type.param_reads

import org.scalatest.{FlatSpec, Inside, Matchers}
import play.api.libs.json._

class PatternValueTest extends FlatSpec with Matchers with Inside {
  "PatternValue" should "parse a JsString to a single item PatternValue list" in {
    val json = JsString("application/json")
    val pv = json.as[List[PatternValue]]
    pv shouldBe List(PatternValue(".*", "application/json"))
  }

  it should "parse an array into a multi item list" in {
    val json = Json.arr(
      Json.obj(
        "pattern" -> ".*\\.json",
        "value" -> "application/json"
      ),
      Json.obj(
        "pattern" -> ".*",
        "value" -> "text/plain"
      )
    )
    val pv = json.as[List[PatternValue]]
    pv shouldBe List(
      PatternValue(".*\\.json", "application/json"),
      PatternValue(".*", "text/plain")
    )
  }

  it should "error if a string or list of objects isn't provided" in {
    val json = JsNumber(123)
    val jsResult = Json.fromJson[List[PatternValue]](json)
    inside(jsResult) {
      case JsError((_, firstError :: Nil) :: Nil) =>
        firstError.message shouldBe "Need a string or an array of objects with pattern and value fields"
    }
  }

  it should "error if the format isn't as expected" in {
    val json = Json.arr(
      Json.obj(
        "pattern" -> ".*\\.json",
        "value" -> "application/json"
      ),
      Json.obj(
        "pattern" -> ".*",
        "monkey" -> "text/plain"
      )
    )
    val jsResult = Json.fromJson[List[PatternValue]](json)
    jsResult should matchPattern {
      case JsError(_) =>
    }
  }
}
