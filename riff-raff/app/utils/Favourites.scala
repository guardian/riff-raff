package utils


import java.util.Base64
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonMappingException
import play.api.libs.json.{ Json => PlayJson }
import play.api.mvc.Cookie

object Favourites {
  def fromCookie(cookie: Cookie): List[String] = {
    try {
      PlayJson.parse(Base64.getDecoder.decode(cookie.value)).validate[List[String]].fold(
        { a =>
          println("Could not parse favourites cookie")
          Nil
        },
        identity
      )
    }  catch {
      case e @ (_: JsonMappingException | _: JsonParseException | _: IllegalArgumentException) =>
        println(s"Failed to parse favourites cookie value: ${cookie.value}", e)
        Nil
    }
  }

  def fromCookie(cookieOpt: Option[Cookie]): List[String] = {
    cookieOpt.map(fromCookie).getOrElse(Nil)
  }

  def toCookie(favourites: List[String]): Cookie = {
    val value = Base64.getEncoder.encodeToString(PlayJson.stringify(PlayJson.toJson(favourites)).getBytes("UTF-8"))
    Cookie("favourites", value, maxAge = Some(86400 * 365), path = "/", secure = true, httpOnly = true)
  }
}
