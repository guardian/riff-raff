package lookup

import conf.Config
import magenta.deployment_type.CloudFormationDeploymentTypeParameters
import org.joda.time.DateTime
import org.scalatest.{FlatSpec, Matchers}
import play.api
import play.api.libs.json.{JsString, Json}
import play.api.libs.ws.WSClient
import play.api.{Configuration, mvc}
import play.api.mvc._
import play.api.mvc.Results._
import play.api.routing.sird._
import play.api.test.WsTestClient
import play.core.server.Server
import resources.{Image, PrismLookup}
import magenta.{ApiCredentials, App, KeyRing, SecretProvider, Stack, Stage}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class PrismLookupTest extends FlatSpec with Matchers {

  val config = new Config(configuration = Configuration(("lookup.timeoutSeconds", 10), ("lookup.prismUrl", "")).underlying, DateTime.now)
  val secretProvider = new SecretProvider {
    override def lookup(service: String, account: String): Option[String] = Some("")
  }

  def withPrismClient[T](images: List[Image])(block: WSClient => T):(T, Option[Request[AnyContent]]) = {
    var mockRequest: Option[Request[AnyContent]] = None
    import scala.concurrent.ExecutionContext.Implicits.global
    val actionBuilder = new api.mvc.ActionBuilder.IgnoringBody()
    def Action: ActionBuilder[Request, AnyContent] = actionBuilder

    val result = Server.withRouter() {
      case GET(p"/images") => Action { request =>
        mockRequest = Some(request)
        Ok(Json.obj(
          "data" -> Json.obj(
            "images" -> Json.toJson(images)
          )
        ))
      }
      case GET(p"/data/keys") => Action { request =>
        mockRequest = Some(request)
        Ok(Json.obj(
          "data" -> Json.obj(
            "keys" -> Json.arr(
              JsString("credentials:aws"), JsString("credentials:aws-role")
            )
          )
        ))
      }
      case GET(url) => Action { request =>
        mockRequest = Some(request)
         if (url.toString().contains("role")) {
           Ok(Json.obj(
             "data" -> Json.obj(
               "stack" -> JsString("deploy"),
               "app" -> JsString(".*"),
               "stage" -> JsString(".*"),
               "value" -> JsString("role ARN"),
               "comment" -> JsString("comment")
             )
           )
           )
         } else {
           Ok(Json.obj(
             "data" -> Json.obj(
               "stack" -> JsString("deploy"),
               "app" -> JsString(".*"),
               "stage" -> JsString(".*"),
               "value" -> JsString("access key"),
               "comment" -> JsString("comment")
             )
           )
           )
         }
      }
    }
    { implicit port =>
      WsTestClient.withClient { client =>
        block(client)
      }
    }
    (result, mockRequest)
  }


  "Keyring" should "correctly generate keyring" in {
    withPrismClient(List()) { client =>
      val lookup = new PrismLookup(config, client, secretProvider)
      val result = lookup.keyRing(stage = Stage("PROD"), app = App("amigo"), stack = Stack("deploy"))
      result.apiCredentials("aws").id shouldBe "access key"
      result.apiCredentials("aws-role").id shouldBe "role ARN"
      result.apiCredentials("aws-role").secret shouldBe "no secret"
    }
  }

  "PrismLookup" should "return latest image" in {
    val images = List(
      Image("test-ami", new DateTime(2017,3,2,13,32,0), Map.empty),
      Image("test-later-ami", new DateTime(2017,4,2,13,32,0), Map.empty),
      Image("test-later-still-ami", new DateTime(2017,5,2,13,32,0), Map.empty),
      Image("test-early-ami", new DateTime(2017,1,2,13,32,0), Map.empty)
    )
    withPrismClient(images) { client =>
      val lookup = new PrismLookup(config, client, secretProvider)
      val result = lookup.getLatestAmi(None, _ => true)("bob")(Map.empty)
      result shouldBe Some("test-later-still-ami")
    }
  }

  it should "narrows ami query by region" in {
    val (result, request) = withPrismClient(Nil) { client =>
      val lookup = new PrismLookup(config, client, secretProvider)
      lookup.getLatestAmi(None, _ => true)("bob")(Map.empty)
    }
    result shouldBe None
    request.flatMap(_.getQueryString("region")) shouldBe Some("bob")
  }

  it should "correctly query using the tags" in {
    val (result, request) = withPrismClient(Nil) { client =>
      val lookup = new PrismLookup(config, client, secretProvider)
      lookup.getLatestAmi(None, _ => true)("bob")(Map("tagName" -> "tagValue?", "tagName*" -> "tagValue2"))
    }
    request.map(_.queryString) shouldBe Some(Map(
      "region" -> ArrayBuffer("bob"),
      "state" -> ArrayBuffer("available"),
      "tags.tagName" -> ArrayBuffer("tagValue?"),
      "tags.tagName*" -> ArrayBuffer("tagValue2")
    ))
  }

  it should "correctly filter out images that are encrypted" in {
    val images = List(
      Image("test-ami", new DateTime(2017,3,2,13,32,0), Map.empty),
      Image("test-later-ami", new DateTime(2017,4,2,13,32,0), Map("Encrypted" -> "very")),
      Image("test-later-still-ami", new DateTime(2017,5,2,13,32,0), Map("Encrypted" -> "true")),
      Image("test-early-ami", new DateTime(2017,1,2,13,32,0), Map.empty)
    )
    withPrismClient(images) { client =>
      val lookup = new PrismLookup(config, client, secretProvider)
      val result = lookup.getLatestAmi(None, CloudFormationDeploymentTypeParameters.unencryptedTagFilter)("bob")(Map.empty)
      result shouldBe Some("test-ami")
    }
  }
}
