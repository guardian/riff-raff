package com.gu.deploy2

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import net.liftweb.json._


class ResolverTest extends FlatSpec with ShouldMatchers {
  val contentApiExample =
    JsonInputFile(
      packages = Map(
        "index-builder" -> JsonPackage("jetty-webapp", List("index-builder")),
        "api" -> JsonPackage("jetty-webapp", List("api")),
        "solr" -> JsonPackage("jetty-webapp", List("solr"))
      ),
      recipes = Map(
        "all" -> JsonRecipe(default = true, depends = List("index-build-only", "api-only")),
        "index-build-only" -> JsonRecipe(actions = List(
          "index-builder.deploy")),
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

    parsed.roles should be (Set(Role("index-builder"), Role("api"), Role("solr")))

    parsed.packages.size should be (3)
    parsed.packages("api") should be (Package(List(Role("api")), JettyWebappPackage()))

    val recipes = parsed.recipes
    recipes.size should be (3)
    recipes("all") should be (Recipe(Nil, List("index-build-only", "api-only")))
  }


}