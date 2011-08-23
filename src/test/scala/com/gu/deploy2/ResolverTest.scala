package com.gu.deploy2


import json._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

class ResolverTest extends FlatSpec with ShouldMatchers {

  val simpleExample = """
  {
    "packages":{
      "htmlapp":{ "type":"file", "roles":["apache"]  }
    },
    "recipes":{
      "all":{
        "default":true,
        "depends":["index-build-only","api-only"]
      },
      "htmlapp-only":{
        "actions":["htmlapp.deploy"],
      }
    }
  }
"""

  "resolver" should "parse json into actions that can be executed" in {
    val parsed = JsonReader.parse(simpleExample)
    val deployRecipe = parsed.recipes("htmlapp-only")

    val deployinfo = Host("host1").roles("apache") :: Nil

    val tasks = Resolver.resolve(deployRecipe, deployinfo)

    tasks.size should be (1)
    tasks should be (List(
      CopyFileTask("packages/htmlapp-only/", "/")
    ))
  }


}