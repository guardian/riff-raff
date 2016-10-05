package magenta
package json

import magenta.artifact.{S3JsonArtifact, S3Path}
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}

case class JsonPackage(
  `type`: String,
  apps: Option[List[String]],
  data: Option[Map[String, JsValue]],
  fileName: Option[String]
) {
  def safeData = data getOrElse Map.empty
}
object JsonPackage {
  implicit val reads = Json.reads[JsonPackage]
}

case class JsonRecipe(
  actionsBeforeApp: Option[List[String]] = None,
  actionsPerHost: Option[List[String]] = None,
  actions: Option[List[String]] = None,
  depends: Option[List[String]] = None
)
object JsonRecipe {
  implicit val reads = Json.reads[JsonRecipe]
}

case class JsonInputFile(
  defaultStacks: Option[List[String]],
  packages: Map[String, JsonPackage],
  recipes: Option[Map[String, JsonRecipe]]
)
object JsonInputFile {
  implicit val reads = Json.reads[JsonInputFile]
}


object JsonReader {
  def parse(s: String, artifact: S3JsonArtifact): Project = {
    val inputFile: JsonInputFile = Json.fromJson[JsonInputFile](Json.parse(s)) match {
      case JsSuccess(result, _) => result
      case JsError(errors) => throw new IllegalArgumentException(s"Failed to parse JSON: $errors")
    }
    parse(inputFile, artifact)
  }
  
  def defaultRecipes(packages: Map[String, DeploymentPackage]) =
    Map("default" -> JsonRecipe(actions = Some(packages.values.map(_.name + ".deploy").toList)))

  private def parse(input: JsonInputFile, artifact: S3JsonArtifact): Project = {
    val packages = input.packages map { case (name, pkg) => name -> parsePackage(name, pkg, artifact) }
    val recipes = input.recipes.getOrElse(defaultRecipes(packages))  map { case (name, r) => name -> parseRecipe(name, r, packages) }

    Project(packages, recipes, input.defaultStacks.getOrElse(Nil).map(NamedStack(_)))
  }


  private def parseRecipe(name: String, jsonRecipe: JsonRecipe, availablePackages: Map[String, DeploymentPackage]) = {
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
      actions = (
        jsonRecipe.actionsBeforeApp.getOrElse(Nil)
          ++ jsonRecipe.actionsPerHost.getOrElse(Nil)
          ++ jsonRecipe.actions.getOrElse(Nil)
        ) map parseAction,
      dependsOn = jsonRecipe.depends.getOrElse(Nil)
    )
  }

  private def parsePackage(name: String, jsonPackage: JsonPackage, artifact: S3JsonArtifact) =
    DeploymentPackage(
      name,
      jsonPackage.apps.getOrElse(Nil) match {
        case Nil => Seq(App(name))
        case x => x.map(App(_))
      },
      jsonPackage.safeData,
      jsonPackage.`type`,
      S3Path(artifact, s"packages/${jsonPackage.fileName.getOrElse(name)}"),
      legacyConfig = true
    )

}


