package magenta

sealed trait Credentials {
  def service: String
  def comment: Option[String]
}
sealed trait ApiCredentials extends Credentials {
  def id: String
  def secret: String
}
case class KeyRing(apiCredentials: Map[String,ApiCredentials] = Map.empty) {
  override def toString = apiCredentials.values.toList.mkString(", ")
}

object ApiCredentials {
  def apply(service: String, id: String, secret: String, comment: Option[String] = None): ApiCredentials =
    DefaultApiCredentials(service, id, secret, comment)
}
case class DefaultApiCredentials(service: String, id: String, secret: String, comment: Option[String]) extends ApiCredentials {
  override def toString = s"$service:$id${comment.map(c => s" ($c)").getOrElse("")}"
}