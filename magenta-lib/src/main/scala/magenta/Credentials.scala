package magenta

case class KeyRing(apiCredentials: Map[String, ApiCredentials] = Map.empty) {
  override def toString = apiCredentials.values.toList.mkString(", ")
}

trait ApiCredentials {
  val service: String
  val id: String
  val comment: Option[String]
  override def toString = s"$service:$id${comment.map(c => s" ($c)").getOrElse("")}"
}

case class ApiStaticCredentials(service: String, id: String, secret: String, comment: Option[String]) extends ApiCredentials
case class ApiRoleCredentials(service: String, id: String, comment: Option[String]) extends ApiCredentials