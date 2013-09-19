package magenta
package json

import net.liftweb.json._
import io.Source
import java.io.{FileNotFoundException, File}


case class JsonInputFile(
  packages: Map[String, JsonPackage],
  recipes: Option[Map[String, JsonRecipe]]
)


case class JsonPackage(
  `type`: String,
  apps: List[String],
  data: Option[Map[String, JValue]] = None,
  fileName: Option[String] = None
) {
  def safeData = data getOrElse Map.empty
}


case class JsonRecipe(
  actionsBeforeApp: List[String] = Nil,
  actionsPerHost: List[String] = Nil,
  @deprecated(message = "only here for backwards compatibility - use 'actionsPerHost'", since = "1.0")
  actions: List[String] = Nil,
  depends: List[String] = Nil
) {
}


object JsonReader {
  private implicit val formats = DefaultFormats

  def parse(f: File): Project = {
    try {
      parse(Source.fromFile(f).mkString, f.getAbsoluteFile.getParentFile)
    } catch {
      case e:FileNotFoundException =>
        MessageBroker.fail("Artifact cannot be deployed: deploy.json file doesn't exist")
    }
  }

  def parse(s: String, artifactSrcDir: File): Project = {
    parse(Extraction.extract[JsonInputFile](JsonParser.parse(s)), artifactSrcDir)
  }
  
  def defaultRecipes(packages: Map[String, Package]) =
    Map("default" -> JsonRecipe(actions = packages.values.map(_.name + ".deploy").toList))

  private def parse(input: JsonInputFile, artifactSrcDir: File): Project = {
    val packages = input.packages map { case (name, pkg) => name -> parsePackage(name, pkg, artifactSrcDir) }
    val recipes = input.recipes.getOrElse(defaultRecipes(packages))  map { case (name, r) => name -> parseRecipe(name, r, packages) }

    Project(packages, recipes)
  }


  private def parseRecipe(name: String, jsonRecipe: JsonRecipe, availablePackages: Map[String, Package]) = {
    def parseAction(actionString: String) = {
      actionString.split("\\.") match {
        case Array(pkgName, actionName) =>
          val pkg = availablePackages.get(pkgName).getOrElse(sys.error(s"Package '$pkgName' does not exist; cannot resolve action '$actionString'"))
          pkg.mkAction(actionName)

        case _ => sys.error(s"Badly formed action name: '$actionString' - should be in <packageName>.<actionName> format")
      }
    }

    Recipe(
      name = name,
      actionsBeforeApp = jsonRecipe.actionsBeforeApp map parseAction,
      actionsPerHost = (jsonRecipe.actionsPerHost ++ jsonRecipe.actions) map parseAction,
      dependsOn = jsonRecipe.depends
    )
  }

  private def parsePackage(name: String, jsonPackage: JsonPackage, artifactSrcDir: File) =
    Package(
      name,
      jsonPackage.apps match {
        case Nil => Set(App(name))
        case x => x.map(App).toSet
      },
      jsonPackage.safeData,
      jsonPackage.`type`,
      new File(artifactSrcDir, "/packages/%s" format(jsonPackage.fileName.getOrElse(name)))
    )

}


