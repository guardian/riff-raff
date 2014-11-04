package ci

import java.net.{URISyntaxException, URI}

object Status extends Enumeration {
  type Status = Value
  val Danger = Value
  val Success = Value
  val Default = Value
}

case class TagClassification(text: String) {
  import TagClassification._

  lazy val status = keywordStatusMap.find { case (keyword, _) => text.toLowerCase.contains(keyword) }.map(_._2)

  lazy val link = text match {
    case HttpMatcher(l) => try {
      Some(new URI(l))
    } catch {
      case e:URISyntaxException => None
    }
    case _ => None
  }
}

object TagClassification {
  val keywordStatusMap = Map(
    "error" -> Status.Danger,
    "fail" -> Status.Danger,
    "success" -> Status.Success,
    "succeed" -> Status.Success,
    "pass" -> Status.Success
  )

  val HttpMatcher = "^.*(http.*)$".r
}
