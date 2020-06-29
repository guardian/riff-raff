package magenta

case class KeyRing(apiCredentials: Map[String, ApiCredentials] = Map.empty) {
  override def toString = apiCredentials.values.toList.mkString(", ")
}

case class ApiCredentials(service: String, id: String, secret: String, comment: Option[String]) {
  override def toString = s"$service:$id${comment.map(c => s" ($c)").getOrElse("")}"
}