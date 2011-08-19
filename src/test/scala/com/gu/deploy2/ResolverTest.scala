package com.gu.deploy2

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec

/*
 ],
  packages: [
    "index-builder": { type: "jetty-webapp", defaultRoles: [ "index-builder" ], data: [ "jetty-app-dir": "index-build" ] },
    "api": { type: "jetty-webapp", defaultRoles: [ "api" ] },
    "solr": { type: "jetty-webapp", defaultRoles: [ "solr" ] }
  ]
  recipes: [
    "all": {
      default: true,
      depends:[ [ "index-build-only", "api-only" ], "rebuild-full-index"]
    },
    "index-build-only": {
      actions: [
        "index-builder.block",
        "index-builder.install",
        "index-builder.restart",
        "index-builder.unblock",
      ]
    },
    "api-only": {
      actions: [
        [ "api.block", "solr.block" ],
        [ "api.install", "solr.install" ],
        [ "api.restart", "solr.restart" ],
        [ "api.unblock", "solr.unblock" ]
      ]
    }



 */


class ResolverTest extends FlatSpec with ShouldMatchers {
  val contentApiExample =
    JsonInputFile(
      roles = List("index-builder", "api", "solr"),
      packages = Map(
        "index-builder" -> JsonPackage("jetty-webapp", List("index-builder")),
        "api" -> JsonPackage("jetty-webapp", List("api")),
        "solr" -> JsonPackage("jetty-webapp", List("solr"))
      ),
      recipes = Map(
        "all" -> JsonRecipe(default = true, depends = List("index-build-only", "api-only")),
        "index-build-only" -> JsonRecipe(actions = List(
          "index-builder.block",
          "index-builder.install",
          "index-builder.restart",
          "index-builder.unblock")),
        "api-only" -> JsonRecipe(actions = List(
          "api.block", "solr.block",
          "api.install", "solr.install",
          "api.restart", "solr.restart",
          "api.unblock", "solr.unblock"
        ))
      )
    )

  "resolver" should "parse json and resolve links" in {
    val parsed = Resolver.parse(contentApiExample)
    println(parsed)

    parsed.roles should be (List(Role("index-builder"), Role("api"), Role("solr")))

    parsed.packages.size should be (3)
    parsed.packages("api") should be (Package(List(Role("api")), JettyWebappPackage()))
  }
}