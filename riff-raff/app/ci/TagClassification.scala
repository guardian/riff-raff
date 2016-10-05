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

  lazy val status = keywordStatusMap.get(text.toLowerCase)

  lazy val link = text match {
    case HttpMatcher(l) =>
      try {
        Some(new URI(l))
      } catch {
        case e: URISyntaxException => None
      }
    case _ => None
  }
}

object TagClassification {
  val keywordStatusMap = Map(
    "error" -> Status.Danger,
    "failed" -> Status.Danger,
    "success" -> Status.Success,
    "passed" -> Status.Success,
    "teststatus:passed" -> Status.Success,
    "teststatus:failed" -> Status.Danger
  )

  val HttpMatcher = "^.*(http.*)$".r
}
