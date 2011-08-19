package com.gu.deploy2



object Resolver {
  def parse(input: JsonInputFile): Install = {
    val packages = input.packages mapValues Package.parse
    val recipes = input.recipes mapValues { Recipe.parse(_, packages)}

    Install(packages, recipes)
  }

}