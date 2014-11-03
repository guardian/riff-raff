package ci

import java.net.{MalformedURLException, URL}

object Colour extends Enumeration {
  type Colour = Value
  val Red = Value
  val Green = Value
}

case class TagClassification(text: String) {
  import TagClassification._

  lazy val colour = keywordColourMap.find { case (keyword, _) => text.toLowerCase.contains(keyword) }.map(_._2)

  lazy val link = text match {
    case HttpMatcher(l) => try {
      Some(new URL(l))
    } catch {
      case e:MalformedURLException => None
    }
    case _ => None
  }
}

object TagClassification {
  val keywordColourMap = Map(
    "error" -> Colour.Red,
    "fail" -> Colour.Red,
    "success" -> Colour.Green,
    "succeed" -> Colour.Green,
    "pass" -> Colour.Green
  )

  val HttpMatcher = "^.*(http.*)$".r
}
