package magenta
package json

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import java.io.File
import net.liftweb.json.JsonAST._
import net.liftweb.json.Implicits._

class JsonReaderTest extends FlatSpec with ShouldMatchers {
  val contentApiExample = """
  {
    "packages":{
      "index-builder":{
        "type":"jetty-webapp",
        "apps":["index-builder"],
      },
      "api":{
        "type":"jetty-webapp",
        "apps":["api"],
        "data": {
          "healthcheck_paths": [
            "/api/index.json",
            "/api/search.json"
          ]
        }
      },
      "solr":{
        "type":"jetty-webapp",
        "apps":["solr"],
        "data": {
          "port": "8400"
        }
      }
    },
    "recipes":{
      "all":{
        "default":true,
        "depends":["index-build-only","api-only"]
      },
      "index-build-only":{
        "default":false,
        "actions":["index-builder.deploy"],
      },
      "api-only":{
        "default":false,
        "actions":[
          "api.deploy","solr.deploy"
        ],
      }
    }
  }
"""

  "json parser" should "parse json and resolve links" in {
    val parsed = JsonReader.parse(contentApiExample, new File("/tmp/abc"))

    parsed.applications should be (Set(App("index-builder"), App("api"), App("solr")))

    parsed.packages.size should be (3)
    parsed.packages("index-builder") should be (Package("index-builder", Set(App("index-builder")), Map.empty, "jetty-webapp", new File("/tmp/abc/packages/index-builder")))
    parsed.packages("api") should be (Package("api", Set(App("api")), Map("healthcheck_paths" -> JArray(List("/api/index.json","/api/search.json"))), "jetty-webapp", new File("/tmp/abc/packages/api")))
    parsed.packages("solr") should be (Package("solr", Set(App("solr")), Map("port" -> "8400"), "jetty-webapp", new File("/tmp/abc/packages/solr")))

    val recipes = parsed.recipes
    recipes.size should be (3)
    recipes("all") should be (Recipe("all", Nil, List("index-build-only", "api-only")))
  }

  val minimalExample = """
{
  "packages": {
    "dinky": { "type": "jetty-webapp" }
  },
  "recipes": {
    "default": {
      "actions": [ "dinky.deploy" ]
    }
  }
}
"""

  "json parser" should "infer a single app if none specified" in {
    val parsed = JsonReader.parse(minimalExample, new File("/tmp/abc"))

    parsed.applications should be (Set(App("dinky")))
  }

}