package com.gu.deploy2


import json._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

class ResolverTest extends FlatSpec with ShouldMatchers {
  val simpleExample =
    JsonInputFile(
      packages = Map(
        "htmlapp" -> JsonPackage("file", List("apache"))
      ),
      recipes = Map(
        "all" -> JsonRecipe(default = true, depends = List("index-build-only", "api-only")),
        "htmlapp-only" -> JsonRecipe(actions = List("htmlapp.deploy"))
      )
    )

  "resolver" should "parse json into actions that can be executed" in {
    val parsed = JsonParser.parse(simpleExample)
    val deployRecipe = parsed.recipes("htmlapp-only")
    val tasks = deployRecipe.actions.flatMap( _.resolve(Host("host1")))
    tasks.size should be (1)
    tasks should be (List(
      CopyFileTask(
        List(
          ("src/file1", "dest/file1")
        )
      )
    ))
  }


}