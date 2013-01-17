package controllers

import play.api.mvc.Controller
import org.joda.time.DateTime
import persistence.Persistence
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import java.security.SecureRandom


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

}