package com.gu.deploy2



case class JsonInputFile(
  packages: Map[String, JsonPackage],
  recipes: Map[String, JsonRecipe]
)

case class JsonPackage(
  `type`: String,
  roles: List[String],
  data: Map[String, String] = Map.empty
)

case class JsonRecipe(
  default: Boolean = false,
  actions: List[String] = Nil,
  depends: List[String] = Nil
)
