package com.gu.deploy2
package json

import net.liftweb.json._


case class JsonInputFile(
  packages: Map[String, JsonPackage],
  recipes: Map[String, JsonRecipe]
)


case class JsonPackage(
  `type`: String,
  roles: List[String],
  data: Option[Map[String, String]] = None
) {
  def safeData = data getOrElse Map.empty
}


case class JsonRecipe(
  default: Option[Boolean] = None,
  actions: List[String] = Nil,
  depends: List[String] = Nil
) {
  def isDefault = default getOrElse false
}



object JsonReader {
  private implicit val formats = DefaultFormats

  def parse(s: String): Install = {
    parse(Extraction.extract[JsonInputFile](JsonParser.parse(s)))
  }

  private def parse(input: JsonInputFile): Install = {
    val packages = input.packages map { case (name, pkg) => name -> parsePackage(name, pkg) }
    val recipes = input.recipes map { case (name, r) => name -> parseRecipe(name, r, packages) }

    Install(packages, recipes)
  }


  private def parseRecipe(name: String, jsonRecipe: JsonRecipe, availablePackages: Map[String, Package]) = {
    def parseAction(actionString: String) = {
      actionString.split("\\.") match {
        case Array(pkgName, actionName) =>
          val pkg = availablePackages.get(pkgName).getOrElse(sys.error("Unknown package in action: " + actionString))
          pkg.mkAction(actionName)

        case _ => sys.error("Badly formed action name: " + actionString)
      }
    }

    Recipe(
      name = name,
      actions = jsonRecipe.actions map parseAction,
      dependsOn = jsonRecipe.depends)
  }

  private def parsePackage(name: String, jsonPackage: JsonPackage) =
    Package(
      name,
      jsonPackage.roles map Role,
      Package.packageTypes.get(jsonPackage.`type`).get
    )

}


