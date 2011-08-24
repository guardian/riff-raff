package com.gu.deploy2

import json.JsonReader
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import java.text.spi.CollatorProvider

class TypesTest extends FlatSpec with ShouldMatchers {
  val jettyJson = """
  {
    "packages":{
      "webapp":{ "type":"jetty-webapp", "roles":["jetty"]  }
    },
    "recipes":{
      "webapp-only":{
        "actions":["webapp.deploy"],
      }
    }
  }
"""
  val jettyRole = Role("jetty")

  val jettyHost = List(Host("the_host").role(jettyRole))

  it should "Jetty Type should have a deploy action" in {
    val parsed = JsonReader.parse(jettyJson)
    val jettyRecipe = parsed.recipes("webapp-only")

    Resolver.resolve(jettyRecipe, jettyHost) should be (List(
      BlockFirewallTask(),
      CopyFileTask("packages/webapp", "/jetty-apps/webapp/"),
      RestartAndWaitTask(),
      UnblockFirewallTask()
    ))
  }


}