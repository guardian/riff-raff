package controllers

import play.api.mvc.Controller
import org.joda.time.DateTime
import persistence.Persistence
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import java.security.SecureRandom
import com.codahale.jerkson.Json._


case class ApiKey(
  application:String,
  key:String,
  issuedBy:String,
  created:DateTime,
  lastUsed:Option[DateTime] = None,
  callCounters:Map[String, Long] = Map.empty
){
  lazy val totalCalls = callCounters.values.fold(0L){_+_}
}

object ApiKeyGenerator {
  lazy val secureRandom = new SecureRandom()

  def newKey(length: Int = 32): String = {
    val rawData = new Array[Byte](length)
    secureRandom.nextBytes(rawData)
    rawData.map{ byteData =>
      val char = (byteData & 63)
      char match {
        case lower if lower < 26 => ('a' + lower).toChar
        case upper if upper >= 26 && upper < 52 => ('A' + (upper - 26)).toChar
        case numeral if numeral >= 52 && numeral < 62 => ('0' + (numeral - 52)).toChar
        case hyphen if hyphen == 62 => '-'
        case underscore if underscore == 63 => '_'
        case default =>
          throw new IllegalStateException("byte value out of expected range")
      }
    }.mkString
  }

}

object Api extends Controller with Logging {

  val applicationForm = Form(
    "application" -> nonEmptyText.verifying("Application name already exists", Persistence.store.getApiKeyByApplication(_).isEmpty)
  )

  val apiKeyForm = Form(
    "key" -> nonEmptyText
  )

  def createKeyForm = AuthAction { implicit request =>
    Ok(views.html.api.form(request, applicationForm))
  }

  def createKey = AuthAction { implicit request =>
    applicationForm.bindFromRequest().fold(
      errors => BadRequest(views.html.api.form(request, errors)),
      applicationName => {
        val randomKey = ApiKeyGenerator.newKey()
        val key = ApiKey(applicationName, randomKey, request.identity.get.fullName, new DateTime())
        Persistence.store.createApiKey(key)
        Redirect(routes.Api.listKeys)
      }
    )
  }

  def listKeys = AuthAction { implicit request =>
    Ok(views.html.api.list(request, Persistence.store.getApiKeyList))
  }

  def delete = AuthAction { implicit request =>
    apiKeyForm.bindFromRequest().fold(
      errors => Redirect(routes.Api.listKeys()),
      apiKey => {
        Persistence.store.deleteApiKey(apiKey)
        Redirect(routes.Api.listKeys())
      }
    )
  }

  def history = ApiAuthAction("history") { implicit request =>
    try {
      val filter = deployment.DeployFilter.fromRequest(request)
      val count = DeployController.countDeploys(filter)
      val pagination = deployment.DeployFilterPagination.fromRequest.withItemCount(Some(count))
      val deployList = DeployController.getDeploys(filter, pagination.pagination, fetchLogs = false).reverse

      val deploys = deployList.map{ deploy =>
        Map(
          "time" -> deploy.time,
          "uuid" -> deploy.uuid,
          "taskType" -> deploy.taskType.toString,
          "projectName" -> deploy.parameters.build.projectName,
          "build" -> deploy.parameters.build.id,
          "stage" -> deploy.parameters.stage.name,
          "deployer" -> deploy.parameters.deployer.name,
          "recipe" -> deploy.parameters.recipe.name,
          "status" -> deploy.state.toString,
          "logURL" -> routes.Deployment.viewUUID(deploy.uuid.toString).absoluteURL()
        )
      }
      val response = Map(
        "response" -> Map(
          "status" -> "ok",
          "total" -> pagination.itemCount,
          "pageSize" -> pagination.pageSize,
          "currentPage" -> pagination.page,
          "pages" -> pagination.pageCount.get,
          "filter" -> filter.map(_.queryStringParams.toMap).getOrElse(Map.empty),
          "results" -> deploys
        )
      )
      Ok(generate(response))
    } catch {
      case t:Throwable =>
        val response = Map(
          "response" -> Map(
            "status" -> "error",
            "message" -> t.getMessage,
            "stacktrace" -> t.getStackTraceString.split("\n")
          )
        )
        Ok(generate(response))
    }
  }

}