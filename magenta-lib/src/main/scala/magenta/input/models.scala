package magenta.input

import play.api.data.validation.ValidationError
import play.api.libs.json._


case class RiffRaffDeployConfig(
  stacks: Option[List[String]],
  regions: Option[List[String]],
  templates: Option[Map[String, DeploymentOrTemplate]],
  deployments: List[(String, DeploymentOrTemplate)]
)
object RiffRaffDeployConfig {
  implicit def readObjectAsList[V](implicit fmtv: Reads[V]) = new Reads[List[(String, V)]] {
    // copied from the map implementation in play.api.libs.json.Reads but builds an ordered
    // list instead of an unordered map
    def reads(json: JsValue): JsResult[List[(String, V)]] = json match {
      case JsObject(linkedMap) =>
        type Errors = Seq[(JsPath, Seq[ValidationError])]
        def locate(e: Errors, key: String) = e.map { case (p, valerr) => (JsPath \ key) ++ p -> valerr }

        linkedMap.foldLeft(Right(Nil): Either[Errors, List[(String, V)]]) {
          case (acc, (key, value)) => (acc, Json.fromJson[V](value)(fmtv)) match {
            case (Right(vs), JsSuccess(v, _)) => Right(vs :+ (key -> v))
            case (Right(_), JsError(e)) => Left(locate(e, key))
            case (Left(e), _: JsSuccess[_]) => Left(e)
            case (Left(e1), JsError(e2)) => Left(e1 ++ locate(e2, key))
          }
        }.fold(JsError.apply, res => JsSuccess(res))
      case _ => JsError(Seq(JsPath() -> Seq(ValidationError("error.expected.jsobject"))))
    }
  }
  implicit val reads: Reads[RiffRaffDeployConfig] = Json.reads
}

/**
  * Represents entries for deployments and templates in a riff-raff.yml.
  * Deployments and deployment templates have the same structure so this class can represent both.
  *
  * @param `type`           The type of deployment to perform (e.g. autoscaling, s3).
  * @param template         Name of the custom deploy template to use for this deployment.
  * @param stacks           Stack tags to apply to this deployment. The deployment will be executed once for each stack.
  * @param regions          A list of the regions in which this deploy will be executed. Defaults to just 'eu-west-1'
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
  app: Option[String],
  contentDirectory: Option[String],
  dependencies: Option[List[String]],
  parameters: Option[Map[String, JsValue]]
)
object DeploymentOrTemplate {
  implicit val reads: Reads[DeploymentOrTemplate] = Json.reads
}

/**
  * A deployment that has been parsed and validated out of a riff-raff.yml file.
  */
case class Deployment(
  name: String,
  `type`: String,
  stacks: List[String],
  regions: List[String],
  app: String,
  contentDirectory: String,
  dependencies: List[String],
  parameters: Map[String, JsValue]
)
