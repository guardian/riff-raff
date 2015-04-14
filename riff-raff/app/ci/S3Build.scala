package ci

import conf.Configuration
import controllers.Logging
import org.joda.time.DateTime
import play.api.libs.json.{JsPath, Json, Reads}

import scala.util.control.NonFatal
import scala.util.{Failure, Try}

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

  def buildJsons: Seq[S3Location] =
    S3Location.all(bucketName).filter(_.path.endsWith("build.json"))

  def buildAt(location: S3Location): Option[S3Build] = (Try {
    log.debug(s"Parsing ${location.path}")
    S3Location.contents(location).map(parse)
  } recoverWith  {
    case NonFatal(e) => {
      log.error(s"Error parsing $location", e)
      Failure(e)
    }
  }).toOption.flatten

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
