package magenta
package json

import magenta.artifact.{S3JsonArtifact, S3Path}
import magenta.deployment_type.DeploymentType
import play.api.data.validation.ValidationError
import play.api.libs.json._

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

  def parse(s: String): Either[Seq[(JsPath, Seq[ValidationError])], JsonInputFile] =
    Json.fromJson[JsonInputFile](Json.parse(s)).asEither
}

object JsonReader {

  def defaultRecipes(packages: Map[String, DeploymentPackage]) =
    Map("default" -> JsonRecipe(actions = Some(packages.values.map(_.name + ".deploy").toList)))

  def buildProject(input: JsonInputFile, artifact: S3JsonArtifact, deploymentTypes: Seq[DeploymentType]): Project = {
    val packages = input.packages map {
      case (name, pkg) => name -> parsePackage(name, pkg, artifact, deploymentTypes)
    }
    val recipes = input.recipes.getOrElse(defaultRecipes(packages)) map {
      case (name, r) => name -> parseRecipe(name, r, packages)
    }

    Project(packages, recipes, input.defaultStacks.getOrElse(Nil).map(NamedStack(_)))
  }

  private def parseRecipe(name: String, jsonRecipe: JsonRecipe, availablePackages: Map[String, DeploymentPackage]) = {
    def parseAction(actionString: String) = {
      actionString.split("\\.") match {
        case Array(pkgName, actionName) =>
          val pkg = availablePackages
            .get(pkgName)
            .getOrElse(sys.error(s"Package '$pkgName' does not exist; cannot resolve action '$actionString'"))
          pkg.mkDeploymentStep(actionName)

        case _ =>
          sys.error(s"Badly formed action name: '$actionString' - should be in <packageName>.<actionName> format")
      }
    }

    Recipe(
      name = name,
      deploymentSteps = (
        jsonRecipe.actionsBeforeApp.getOrElse(Nil)
          ++ jsonRecipe.actionsPerHost.getOrElse(Nil)
          ++ jsonRecipe.actions.getOrElse(Nil)
      ) map parseAction,
      dependsOn = jsonRecipe.depends.getOrElse(Nil)
    )
  }

  private def parsePackage(name: String,
                           jsonPackage: JsonPackage,
                           artifact: S3JsonArtifact,
                           deploymentTypes: Seq[DeploymentType]) =
    DeploymentPackage(
      name,
      jsonPackage.apps.getOrElse(Nil) match {
        case Nil => Seq(App(name))
        case x => x.map(App(_))
      },
      jsonPackage.safeData,
      jsonPackage.`type`,
      S3Path(artifact, s"packages/${jsonPackage.fileName.getOrElse(name)}/"),
      legacyConfig = true,
      deploymentTypes
    )

}
