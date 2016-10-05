package ci

import conf.Configuration
import controllers.Logging
import magenta.artifact.{S3Location, S3Object}
import org.joda.time.DateTime
import play.api.libs.json.{JsPath, Json, Reads}

import scala.util.Try
import scala.util.control.NonFatal

case class S3Build(
    id: Long,
    jobName: String,
    jobId: String,
    branchName: String,
    number: String,
    startTime: DateTime,
    revision: String,
    vcsURL: String
) extends CIBuild

case class S3Project(id: String, name: String) extends Job

object S3Build extends Logging {

  lazy val bucketName = Configuration.build.aws.bucketName.get
  implicit lazy val client = Configuration.build.aws.client

  def buildJsons: Seq[S3Object] =
    Try {
      S3Location.listAll(bucketName).filter(_.key.endsWith("build.json"))
    } recover {
      case NonFatal(e) =>
        log.error(s"Error finding buildJsons", e)
        Nil
    } get

  def buildAt(location: S3Object): Option[S3Build] =
    Try {
      log.debug(s"Parsing ${location.key}")
      location.fetchContentAsString().map(parse)
    } recover {
      case NonFatal(e) =>
        log.error(s"Error parsing $location", e)
        None
    } get

  def parse(json: String): S3Build = {
    import play.api.libs.functional.syntax._
    import utils.Json.DefaultJodaDateReads
    implicit val reads: Reads[S3Build] = (
      (JsPath \ "buildNumber").read[String].map(_.toLong) and
        (JsPath \ "projectName").read[String] and
        (JsPath \ "projectName").read[String] and
        (JsPath \ "branch").read[String] and
        (JsPath \ "buildNumber").read[String] and
        (JsPath \ "startTime").read[DateTime] and
        (JsPath \ "revision").read[String] and
        (JsPath \ "vcsURL").read[String]
    )(S3Build.apply _)

    Json.parse(json).as[S3Build]
  }
}
