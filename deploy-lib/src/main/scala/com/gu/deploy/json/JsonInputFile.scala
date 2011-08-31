package com.gu.deploy
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

  def parse(f: File): Project = parse(Source.fromFile(f).mkString, f)

  def parse(s: String, artifactSrcDir: File): Project = {
    parse(Extraction.extract[JsonInputFile](JsonParser.parse(s)), artifactSrcDir)
  }

  private def parse(input: JsonInputFile, artifactSrcDir: File): Project = {
    val packages = input.packages map { case (name, pkg) => name -> parsePackage(name, pkg, artifactSrcDir) }
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

  private def parsePackage(name: String, jsonPackage: JsonPackage, artifactSrcDir: File) =
    Package(
      name,
      jsonPackage.roles.map(Role).toSet,
      jsonPackage.safeData,
      jsonPackage.`type`,
      new File(artifactSrcDir.getPath() + "/packages/%s" format(name))
    )

}


