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

    val deployinfo = Host("host1").roleNames("apache") :: Nil

    val tasks = Resolver.resolve(deployRecipe, deployinfo)

    tasks.size should be (1)
    tasks should be (List(
      CopyFileTask("packages/htmlapp-only/", "/")
    ))
  }

  case class StubTask(description: String) extends Task {
    def execute() { }
  }

  case class StubAction(description: String, roles: Set[Role]) extends Action {
    def resolve(host: Host) = StubTask(description + "_task on " + host.name) :: Nil
  }

  it should "generate the tasks from the actions supplied" in {
    val role = Role("the_role")

    val recipe = Recipe(
      name = "one",
      actions = StubAction("action_one", Set(role)) :: Nil,
      dependsOn = Nil)

    val deployinfo = List(Host("the_host", Set(role)))

    val result = Resolver.resolve(recipe, deployinfo)
    result should be (List(
      StubTask("action_one_task on the_host")
    ))
  }


}