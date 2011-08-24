package com.gu.deploy2
package json

import net.liftweb.json._
import io.Source
import java.io.File


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
  actions: List[String] = Nil,
  depends: List[String] = Nil
)


object JsonReader {
  private implicit val formats = DefaultFormats

  def parse(f: File): Project = parse(Source.fromFile(f).mkString)

  def parse(s: String): Project = {
    parse(Extraction.extract[JsonInputFile](JsonParser.parse(s)))
  }

  private def parse(input: JsonInputFile): Project = {
    val packages = input.packages map { case (name, pkg) => name -> parsePackage(name, pkg) }
    val recipes = input.recipes map { case (name, r) => name -> parseRecipe(name, r, packages) }

    Project(packages, recipes)
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
      jsonPackage.roles.map(Role).toSet,
      Types.packageTypes.get(jsonPackage.`type`).get
    )

}


