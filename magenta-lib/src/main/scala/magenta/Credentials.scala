package magenta

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider


case class KeyRing(apiCredentials: Map[String, ApiCredentials] = Map.empty, riffRaffCredentialsProvider: Option[AwsCredentialsProvider] = None) {
  override def toString = apiCredentials.values.toList.mkString(", ")
}

case class ApiCredentials(service: String, id: String, secret: String, comment: Option[String], role: Option[String] = None) {
  override def toString = s"$service:$id${comment.map(c => s" ($c)").getOrElse("")}"
}