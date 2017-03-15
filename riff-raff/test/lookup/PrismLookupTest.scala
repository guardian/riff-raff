package lookup

import mockws.MockWS
import org.joda.time.DateTime
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.Json
import play.api.mvc.Results._
import play.api.mvc.{Action, Request}
import play.api.test.Helpers._
import resources.{Image, PrismLookup}

import scala.collection.mutable.ArrayBuffer

class PrismLookupTest extends FlatSpec with Matchers {
  "PrismLookup" should "return latest image" in {
    val images = List(
      Image("test-ami", new DateTime(2017,3,2,13,32,0)),
      Image("test-later-ami", new DateTime(2017,4,2,13,32,0)),
      Image("test-later-still-ami", new DateTime(2017,5,2,13,32,0)),
      Image("test-early-ami", new DateTime(2017,1,2,13,32,0))
    )
    val mockClient = MockWS{
      case (GET, _) => Action { request =>
        Ok(Json.obj(
          "data" -> Json.obj(
            "images" -> Json.toJson(images)
          )
        ))
      }
    }
    val lookup = new PrismLookup(mockClient)
    val result = lookup.getLatestAmi("bob")(Map.empty)
    result shouldBe Some("test-later-still-ami")
  }

  it should "narrows ami query by region" in {
    var mockRequest: Option[Request[_]] = None
    val mockClient = MockWS{
      case (GET, _) => Action { request =>
        mockRequest = Some(request)
        Ok(Json.obj(
          "data" -> Json.obj(
            "images" -> Json.arr()
          )
        ))
      }
    }
    val lookup = new PrismLookup(mockClient)
    val result = lookup.getLatestAmi("bob")(Map.empty)
    result shouldBe None
    mockRequest.flatMap(_.getQueryString("region")) shouldBe Some("bob")
  }

  it should "correctly query using the tags" in {
    var mockRequest: Option[Request[_]] = None
    val mockClient = MockWS{
      case (GET, _) => Action { request =>
        mockRequest = Some(request)
        Ok(Json.obj(
          "data" -> Json.obj(
            "images" -> Json.arr()
          )
        ))
      }
    }
    val lookup = new PrismLookup(mockClient)
    lookup.getLatestAmi("bob")(Map("tagName" -> "tagValue?", "tagName*" -> "tagValue2"))
    mockRequest.map(_.queryString) shouldBe Some(Map(
      "region" -> ArrayBuffer("bob"),
      "tags.tagName" -> ArrayBuffer("tagValue?"),
      "tags.tagName*" -> ArrayBuffer("tagValue2")
    ))
  }
}
