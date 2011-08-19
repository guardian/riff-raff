package com.gu.deploy2



case class JsonInputFile(
  roles: List[String],
  packages: Map[String, JsonPackage],
  recipes: Map[String, JsonRecipe]
)

case class JsonPackage(
  `type`: String,
  defaultRoles: List[String],
  data: Map[String, String] = Map.empty
)

case class JsonRecipe(
  default: Boolean = false,
  actions: List[String] = Nil,
  depends: List[String] = Nil
)
