package magenta.input

import cats.data.{NonEmptyList => NEL}
import cats.kernel.Semigroup
import play.api.libs.json._

case class ConfigError(context: String, message: String)
case class ConfigErrors(errors: NEL[ConfigError])
object ConfigErrors {
  def apply(context: String, message: String): ConfigErrors = ConfigErrors(ConfigError(context, message))
  def apply(error: ConfigError): ConfigErrors = ConfigErrors(NEL.of(error))
  implicit val sg = new Semigroup[ConfigErrors] {
    def combine(x: ConfigErrors, y: ConfigErrors): ConfigErrors = ConfigErrors(x.errors.concat(y.errors))
  }
}

/** Represents a deployment shown to a user. These are flattened when the graph is built so we only display a
  * deployment that has one each of name, action, region and stack. This also limits the complexity of the selection
  * that needs to be done when deploying. A deployment key can be represented as a string and there are some methods
  * for doing that at the package level.
  */
case class DeploymentKey(name: String, action: String, stack: String, region: String)
object DeploymentKey {

  /** Turn a deployment into a deployment key - WARNING: this doesn't make any checks about the number of elements
    * but will only work predictably with one element in each of actions, stacks and regions */
  def apply(deployment: Deployment): DeploymentKey = {
    DeploymentKey(deployment.name, deployment.actions.head, deployment.stacks.head, deployment.regions.head)
  }

  // serialisation and de-serialisation code for deployment keys
  val DEPLOYMENT_DELIMITER = '!'
  val FIELD_DELIMITER = '*'
  def asString(d: List[DeploymentKey]): String = {
    d.map(asString).mkString(DEPLOYMENT_DELIMITER.toString)
  }
  def asString(d: DeploymentKey): String = {
    List(d.name, d.action, d.stack, d.region).mkString(FIELD_DELIMITER.toString)
  }
  def fromString(s: String) = s.split(FIELD_DELIMITER).toList match {
    case name :: action :: stack :: region :: Nil => Some(DeploymentKey(name, action, stack, region))
    case _ => None
  }
  def fromStringToList(s: String): List[DeploymentKey] = s.split(DEPLOYMENT_DELIMITER).toList.flatMap(fromString)

}

sealed trait DeploymentSelector
case object All extends DeploymentSelector
case class DeploymentKeysSelector(keys: List[DeploymentKey]) extends DeploymentSelector

case class RiffRaffDeployConfig(
    stacks: Option[List[String]],
    regions: Option[List[String]],
    templates: Option[Map[String, DeploymentOrTemplate]],
    deployments: List[(String, DeploymentOrTemplate)]
)
object RiffRaffDeployConfig {
  import RiffRaffYamlReader.readObjectAsList
  implicit val reads: Reads[RiffRaffDeployConfig] = checkedReads(Json.reads)
}

/**
  * Represents entries for deployments and templates in a riff-raff.yml.
  * Deployments and deployment templates have the same structure so this class can represent both.
  *
  * @param `type`           The type of deployment to perform (e.g. autoscaling, s3).
  * @param template         Name of the custom deploy template to use for this deployment.
  * @param stacks           Stack tags to apply to this deployment. The deployment will be executed once for each stack.
  * @param regions          A list of the regions in which this deploy will be executed. Defaults to just 'eu-west-1'
  * @param actions          Override the list of actions to execute for this deployment type.
  * @param app              The `app` tag to use for this deployment. By default the deployment's key is used.
  * @param contentDirectory The path where this deployment is found in the build output. Defaults to app.
  * @param dependencies     This deployment's execution will be delayed until all named dependencies have completed. (Default empty)
  * @param parameters       Provides additional parameters to the deployment type. Refer to the deployment types to see what is required.
  */
case class DeploymentOrTemplate(
    `type`: Option[String],
    template: Option[String],
    stacks: Option[List[String]],
    regions: Option[List[String]],
    actions: Option[List[String]],
    app: Option[String],
    contentDirectory: Option[String],
    dependencies: Option[List[String]],
    parameters: Option[Map[String, JsValue]]
)
object DeploymentOrTemplate {
  implicit val reads: Reads[DeploymentOrTemplate] = checkedReads(Json.reads)
}

/**
  * A deployment that has been parsed and validated out of a riff-raff.yml file.
  */
case class Deployment(
    name: String,
    `type`: String,
    stacks: NEL[String],
    regions: NEL[String],
    actions: NEL[String],
    app: String,
    contentDirectory: String,
    dependencies: List[String],
    parameters: Map[String, JsValue]
)
