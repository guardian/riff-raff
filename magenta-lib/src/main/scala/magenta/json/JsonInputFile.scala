package magenta
package json

import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.JsonParser

import scala.io.Source
import java.io.{File, FileNotFoundException}

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.AmazonS3Exception
import magenta.artifact.S3Artifact


case class JsonInputFile(
  defaultStacks: List[String],
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

  def parse(s: String, artifact: S3Artifact): Project = {
    parse(Extraction.extract[JsonInputFile](JsonParser.parse(s)), artifact)
  }
  
  def defaultRecipes(packages: Map[String, DeploymentPackage]) =
    Map("default" -> JsonRecipe(actions = packages.values.map(_.name + ".deploy").toList))

  private def parse(input: JsonInputFile, artifact: S3Artifact): Project = {
    val packages = input.packages map { case (name, pkg) => name -> parsePackage(name, pkg, artifact) }
    val recipes = input.recipes.getOrElse(defaultRecipes(packages))  map { case (name, r) => name -> parseRecipe(name, r, packages) }

    Project(packages, recipes, input.defaultStacks.map(NamedStack(_)))
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
      actionsBeforeApp = jsonRecipe.actionsBeforeApp map parseAction,
      actionsPerHost = (jsonRecipe.actionsPerHost ++ jsonRecipe.actions) map parseAction,
      dependsOn = jsonRecipe.depends
    )
  }

  private def parsePackage(name: String, jsonPackage: JsonPackage, artifact: S3Artifact) =
    DeploymentPackage(
      name,
      jsonPackage.apps match {
        case Nil => Seq(App(name))
        case x => x.map(App(_))
      },
      jsonPackage.safeData,
      jsonPackage.`type`,
      artifact.getPackage(jsonPackage.fileName.getOrElse(name))
    )

}


