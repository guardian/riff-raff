package controllers

import java.security.SecureRandom
import java.util.UUID
import cats.data.Validated.{Invalid, Valid}
import com.gu.googleauth.AuthAction
import conf.Config
import deployment.{ApiRequestSource, DeployFilter, Deployments, Record}
import magenta.Strategy.MostlyHarmless
import magenta._
import magenta.deployment_type.DeploymentType
import magenta.input.All
import magenta.input.resolver.{Resolver => YamlResolver}
import org.joda.time.{DateTime, LocalDate}
import persistence.DataStore
import play.api.data.Forms._
import play.api.data._
import play.api.i18n.I18nSupport
import play.api.libs.functional.syntax._
import play.api.libs.json.Json.toJson
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.mvc.{Action, _}
import scalikejdbc._
import utils.Json._
import utils.{ChangeFreeze, Graph, LogAndSquashBehaviour}

case class ApiKey(
    application: String,
    key: String,
    issuedBy: String,
    created: DateTime,
    lastUsed: Option[DateTime] = None,
    callCounters: Map[String, Long] = Map.empty
) {
  lazy val totalCalls = callCounters.values.fold(0L) { _ + _ }
}

object ApiKey extends SQLSyntaxSupport[ApiKey] {
  implicit def formats: Format[ApiKey] = Json.format[ApiKey]

  def apply(res: WrappedResultSet): ApiKey =
    Json.parse(res.string(1)).as[ApiKey]

  override val tableName = "apikey"
}

object ApiKeyGenerator {
  lazy val secureRandom = new SecureRandom()

  def newKey(length: Int = 32): String = {
    val rawData = new Array[Byte](length)
    secureRandom.nextBytes(rawData)
    rawData.map { byteData =>
      val char = byteData & 63
      char match {
        case lower if lower < 26                => ('a' + lower).toChar
        case upper if upper >= 26 && upper < 52 => ('A' + (upper - 26)).toChar
        case numeral if numeral >= 52 && numeral < 62 =>
          ('0' + (numeral - 52)).toChar
        case hyphen if hyphen == 62         => '-'
        case underscore if underscore == 63 => '_'
        case default                        =>
          throw new IllegalStateException("byte value out of expected range")
      }
    }.mkString
  }

}

class Api(
    config: Config,
    menu: Menu,
    deployments: Deployments,
    deploymentTypes: Seq[DeploymentType],
    datastore: DataStore,
    changeFreeze: ChangeFreeze,
    authAction: ActionBuilder[AuthAction.UserIdentityRequest, AnyContent],
    val controllerComponents: ControllerComponents
)(implicit val wsClient: WSClient)
    extends BaseController
    with Logging
    with I18nSupport
    with LogAndSquashBehaviour {

  object ApiJsonEndpoint {
    val INTERNAL_KEY = ApiKey("internal", "n/a", "n/a", new DateTime())

    def apply[A](
        authenticatedRequest: ApiRequest[A]
    )(f: ApiRequest[A] => JsValue): Result = {
      val jsonpCallback =
        authenticatedRequest.queryString.get("callback").map(_.head)

      val response =
        try {
          f(authenticatedRequest)
        } catch {
          case t: Throwable =>
            toJson(
              Map(
                "response" -> toJson(
                  Map(
                    "status" -> toJson("error"),
                    "message" -> toJson(t.getMessage),
                    "stacktrace" -> toJson(t.getStackTrace.map(_.toString))
                  )
                )
              )
            )
        }

      val responseObject = response match {
        case jso: JsObject => jso
        case jsv: JsValue  => JsObject(Seq(("value", jsv)))
      }

      jsonpCallback map { callback =>
        Ok(s"$callback(${responseObject.toString})")
          .as("application/javascript")
      } getOrElse {
        response \ "response" \ "status" match {
          case JsDefined(JsString("ok"))    => Ok(responseObject)
          case JsDefined(JsString("error")) => BadRequest(responseObject)
          case _                            =>
            throw new IllegalStateException(
              "Response status missing or invalid"
            )
        }
      }
    }

    def apply[A](counter: String, p: BodyParser[A])(
        f: ApiRequest[A] => JsValue
    ): Action[A] =
      apiAuthAction(counter, p) { apiRequest => this.apply(apiRequest)(f) }

    def apply(counter: String)(
        f: ApiRequest[AnyContent] => JsValue
    ): Action[AnyContent] =
      this.apply(counter, parse.anyContent)(f)

    def withAuthAccess(
        f: ApiRequest[AnyContent] => JsValue
    ): Action[AnyContent] = authAction { request =>
      val apiRequest: ApiRequest[AnyContent] =
        new ApiRequest[AnyContent](INTERNAL_KEY, request)
      this.apply(apiRequest)(f)
    }

    def apiAuthAction[A](counter: String, p: BodyParser[A])(
        f: ApiRequest[A] => Result
    ): Action[A] = {
      Action(p) { implicit request =>
        request.queryString.get("key").flatMap(_.headOption) match {
          case Some(urlParam) =>
            datastore.getAndUpdateApiKey(urlParam, Some(counter)) match {
              case Some(apiKey) => f(new ApiRequest(apiKey, request))
              case None         =>
                Unauthorized(
                  "The API key provided is not valid. Please check and try again."
                )
            }
          case None =>
            Unauthorized(
              "An API key must be provided for this endpoint. Please include a 'key' URL parameter."
            )
        }
      }
    }

  }

  val applicationForm = Form(
    "application" -> nonEmptyText.verifying(
      "Application name already exists",
      datastore.getApiKeyByApplication(_).isEmpty
    )
  )

  val apiKeyForm = Form(
    "key" -> nonEmptyText
  )

  def createKeyForm = authAction { implicit request =>
    Ok(views.html.api.form(config, menu)(applicationForm))
  }

  def createKey = authAction { implicit request =>
    applicationForm
      .bindFromRequest()
      .fold(
        errors => BadRequest(views.html.api.form(config, menu)(errors)),
        applicationName => {
          val randomKey = ApiKeyGenerator.newKey()
          val key = ApiKey(
            applicationName,
            randomKey,
            request.user.fullName,
            new DateTime()
          )
          datastore.createApiKey(key)
          Redirect(routes.Api.listKeys)
        }
      )
  }

  def listKeys = authAction { implicit request =>
    datastore.getApiKeyList.fold(
      (t: Throwable) =>
        InternalServerError(
          views.html.errorContent(t, "Could not fetch API keys")
        ),
      (ks: Iterable[ApiKey]) =>
        Ok(views.html.api.list(config, menu)(request, ks))
    )
  }

  def delete = authAction { implicit request =>
    apiKeyForm
      .bindFromRequest()
      .fold(
        errors => Redirect(routes.Api.listKeys),
        apiKey => {
          datastore.deleteApiKey(apiKey)
          Redirect(routes.Api.listKeys)
        }
      )
  }

  def historyGraph = ApiJsonEndpoint.withAuthAccess { implicit request =>
    val filter = deployment.DeployFilter
      .fromRequest(request)
      .map(_.withMaxDaysAgo(Some(90)))
      .orElse(Some(DeployFilter(maxDaysAgo = Some(30))))
    val count = deployments.countDeploys(filter)
    val pagination = deployment.DeployFilterPagination.fromRequest
      .withItemCount(Some(count))
      .withPageSize(None)
    val deployList = deployments
      .getDeploys(filter, pagination.pagination, fetchLogs = false)
      .logAndSquashException(Nil)

    def description(state: RunState) =
      String.valueOf(state) + " deploys" + filter
        .map { f =>
          f.projectName
            .map(" of " + _)
            .getOrElse("") + f.stage.map(" in " + _).getOrElse("")
        }
        .getOrElse("")

    implicit val dateOrdering: Ordering[LocalDate] = new Ordering[LocalDate] {
      override def compare(x: LocalDate, y: LocalDate): Int = x.compareTo(y)
    }
    val allDataByDay =
      deployList.groupBy(_.time.toLocalDate).mapValues(_.size).toList.sortBy {
        case (day, _) => day
      }
    val firstDate = allDataByDay.headOption.map(_._1)
    val lastDate = allDataByDay.lastOption.map(_._1)

    val deploysByState = deployList.groupBy(_.state).toList.sortBy {
      case (RunState.Completed, _)  => 1
      case (RunState.Failed, _)     => 2
      case (RunState.Running, _)    => 3
      case (RunState.NotRunning, _) => 4
      case default                  => 5
    }

    val deploys = deploysByState.map { case (state, deploysInThatState) =>
      val seriesDataByDay = deploysInThatState
        .groupBy(_.time.toLocalDate)
        .mapValues(_.size)
        .toList
        .sortBy { case (day, _) =>
          day
        }
      val seriesJson =
        Graph.zeroFillDays(seriesDataByDay, firstDate, lastDate).map {
          case (day, deploysOnThatDay) =>
            toJson(
              Map(
                "x" -> toJson(day.toDateTimeAtStartOfDay.getMillis / 1000),
                "y" -> toJson(deploysOnThatDay)
              )
            )
        }
      Map(
        "data" -> toJson(seriesJson),
        "points" -> toJson(seriesJson.length),
        "deploystate" -> toJson(state.toString),
        "name" -> toJson(description(state))
      )
    }

    toJson(
      Map(
        "response" -> toJson(
          Map(
            "series" -> toJson(deploys),
            "status" -> toJson("ok")
          )
        )
      )
    )
  }

  def record2apiResponse(deploy: Record)(implicit
      request: ApiRequest[AnyContent]
  ) =
    Json.obj(
      "time" -> deploy.time,
      "uuid" -> deploy.uuid.toString,
      "projectName" -> deploy.parameters.build.projectName,
      "build" -> deploy.parameters.build.id,
      "stage" -> deploy.parameters.stage.name,
      "deployer" -> deploy.parameters.deployer.name,
      "status" -> deploy.state.toString,
      "logURL" -> routes.DeployController
        .viewUUID(deploy.uuid.toString)
        .absoluteURL(),
      "tags" -> toJson(deploy.allMetaData),
      "durationSeconds" -> deploy.timeTaken.toStandardSeconds.getSeconds
    )

  def history = ApiJsonEndpoint("history") { implicit request =>
    val filter = deployment.DeployFilter.fromRequest(request)
    val count = deployments.countDeploys(filter)
    val pagination =
      deployment.DeployFilterPagination.fromRequest.withItemCount(Some(count))
    val deployList = deployments
      .getDeploys(filter, pagination.pagination, fetchLogs = false)
      .logAndSquashException(Nil)
      .reverse

    val deploys = deployList.map { record2apiResponse }
    val response = Map(
      "response" -> toJson(
        Map(
          "status" -> toJson("ok"),
          "total" -> toJson(pagination.itemCount),
          "pageSize" -> toJson(pagination.pageSize),
          "currentPage" -> toJson(pagination.page),
          "pages" -> toJson(pagination.pageCount.get),
          "filter" -> toJson(
            filter
              .map(_.queryStringParams.toMap.mapValues(toJson(_)))
              .getOrElse(Map.empty)
          ),
          "results" -> toJson(deploys)
        )
      )
    )
    toJson(response)
  }

  val deployRequestReader =
    (__ \ "project").read[String] and
      (__ \ "build").read[String] and
      (__ \ "stage").read[String] tupled

  def deploy = ApiJsonEndpoint("deploy", parse.json) { implicit request =>
    deployRequestReader
      .reads(request.body)
      .fold(
        valid = { deployRequest =>
          val (project, build, stage) = deployRequest
          val params = DeployParameters(
            Deployer(request.fullName),
            Build(project, build),
            Stage(stage),
            updateStrategy = MostlyHarmless
          )
          assert(
            !changeFreeze.frozen(stage),
            s"Deployment to $stage is frozen (API disabled, use the web interface if you need to deploy): ${changeFreeze.message}"
          )

          deployments.deploy(
            params,
            requestSource = ApiRequestSource(request.apiKey)
          ) match {
            case Right(deployId) =>
              Json.obj(
                "response" -> Json.obj(
                  "status" -> "ok",
                  "request" -> Json.obj(
                    "project" -> project,
                    "build" -> build,
                    "stage" -> stage
                  ),
                  "uuid" -> deployId.toString,
                  "logURL" -> routes.DeployController
                    .viewUUID(deployId.toString)
                    .absoluteURL()
                )
              )
            case Left(error) =>
              Json.obj(
                "response" -> Json.obj(
                  "status" -> "error",
                  "errors" -> Json.arr(error.message)
                )
              )
          }
        },
        invalid = { error =>
          Json.obj(
            "response" -> Json.obj(
              "status" -> "error",
              "errors" -> JsError.toJson(error)
            )
          )
        }
      )
  }

  def view(uuid: String) = ApiJsonEndpoint("viewDeploy") { implicit request =>
    val record = deployments.get(UUID.fromString(uuid), fetchLog = false)
    Json.obj(
      "response" -> Json.obj(
        "status" -> "ok",
        "deploy" -> record2apiResponse(record)
      )
    )
  }

  def stop = ApiJsonEndpoint("stopDeploy") { implicit request =>
    Form("uuid" -> nonEmptyText)
      .bindFromRequest()
      .fold(
        _ => throw new IllegalArgumentException("No UUID specified"),
        uuid => {
          val record = deployments.get(UUID.fromString(uuid), fetchLog = false)
          assert(
            !record.isDone,
            "Can't stop a deploy that has already completed"
          )
          deployments.stop(UUID.fromString(uuid), request.fullName)
          Json.obj(
            "response" -> Json.obj(
              "status" -> "ok",
              "message" -> "Set stop flag for deploy",
              "uuid" -> uuid
            )
          )
        }
      )
  }

  def validate = ApiJsonEndpoint("validate") { implicit request =>
    request.body.asText.fold {
      Json.obj(
        "response" -> Json.obj(
          "status" -> "error",
          "errors" -> Json.arr("No configuration provided in request body")
        )
      )
    } { body =>
      val validatedGraph =
        YamlResolver.resolveDeploymentGraph(body, deploymentTypes, All)
      validatedGraph match {
        case Valid(graph) =>
          Json.obj(
            "response" -> Json.obj(
              "status" -> "ok",
              "result" -> "passed",
              "deployments" -> graph.toList.map { deployment =>
                Json.obj(
                  "name" -> deployment.name,
                  "type" -> deployment.`type`,
                  "stacks" -> deployment.stacks.toList,
                  "regions" -> deployment.regions.toList,
                  "actions" -> deployment.actions.toList,
                  "app" -> deployment.app,
                  "contentDirectory" -> deployment.contentDirectory,
                  "dependencies" -> deployment.dependencies,
                  "parameters" -> deployment.parameters
                )
              }
            )
          )
        case Invalid(errors) =>
          Json.obj(
            "response" -> Json.obj(
              "status" -> "ok",
              "result" -> "failed",
              "errors" -> errors.errors.toList.map { err =>
                Json.obj(
                  "context" -> err.context,
                  "message" -> err.message
                )
              }
            )
          )
      }
    }
  }
}
