package com.gu.deploy2
package json


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



object JsonParser {
  def parse(input: JsonInputFile): Install = {
    val packages = input.packages mapValues parsePackage
    val recipes = input.recipes mapValues { parseRecipe(_, packages) }

    Install(packages, recipes)
  }


  private def parseRecipe(jsonRecipe: JsonRecipe, availablePackages: Map[String, Package]) = {
    def parseAction(actionString: String) = {
      actionString.split("\\.") match {
        case Array(pkgName, actionName) =>
          val pkg = availablePackages.get(pkgName).getOrElse(sys.error("Unknown package in action: " + actionString))
          pkg.action(actionName)

        case _ => sys.error("Badly formed action name: " + actionString)
      }
    }

    Recipe(
      actions = jsonRecipe.actions map parseAction,
      dependsOn = jsonRecipe.depends)
  }

  private def parsePackage(jsonPackage: JsonPackage) =
    Package(
      jsonPackage.roles map Role,
      Package.packages.get(jsonPackage.`type`).get
    )

}


