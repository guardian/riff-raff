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

    val deployinfo = Host("host1").role("apache") :: Nil

    val tasks = Resolver.resolve(deployRecipe, deployinfo)

    tasks.size should be (1)
    tasks should be (List(
      CopyFileTask("packages/htmlapp", "/")
    ))
  }

  case class StubTask(description: String) extends Task {
    def execute() { }
  }

  case class StubAction(description: String, roles: Set[Role]) extends Action {
    def resolve(host: Host) = StubTask(description + "_task on " + host.name) :: Nil
  }

  val role = Role("the_role")

  val baseRecipe = Recipe("one",
    actions = StubAction("action_one", Set(role)) :: Nil,
    dependsOn = Nil)

  val deployinfoSingleHost = List(Host("the_host").role(role))

  val deployinfoTwoHosts =
    List(Host("host_one").role(role), Host("host_two").role(role))


  it should "generate the tasks from the actions supplied" in {
    Resolver.resolve(baseRecipe, deployinfoSingleHost) should be (List(
      StubTask("action_one_task on the_host")
    ))
  }

  it should "only generate tasks for hosts that have roles" in {
    Resolver.resolve(baseRecipe, Host("other_host").role("other_role") :: deployinfoSingleHost) should be (List(
      StubTask("action_one_task on the_host")
    ))
  }

  it should "generate tasks for all hosts in role" in {
    Resolver.resolve(baseRecipe, deployinfoTwoHosts) should be (List(
      StubTask("action_one_task on host_one"),
      StubTask("action_one_task on host_two")
    ))
  }

  it should "resolve all actions for a given host before moving on to the next host" in {
    val recipe = Recipe("multi-action",
        actions =
          StubAction("action_one", Set(role)) ::
          StubAction("action_two", Set(role)) ::
          Nil)

    Resolver.resolve(recipe, deployinfoTwoHosts) should be (List(
      StubTask("action_one_task on host_one"),
      StubTask("action_two_task on host_one"),
      StubTask("action_one_task on host_two"),
      StubTask("action_two_task on host_two")
    ))
  }


}