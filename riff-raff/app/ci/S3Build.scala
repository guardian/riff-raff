package ci

import ci.teamcity.Job
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{S3ObjectSummary, ObjectListing}
import controllers.Logging
import org.joda.time.DateTime
import play.api.libs.json.{Json, JsPath, Reads}
import rx.lang.scala.Observable

import scala.annotation.tailrec
import scala.io.Source
import scala.util.{Failure, Try}
import scala.util.control.NonFatal

case class S3Build(
  id: Long,
  jobName: String,
  jobId: String,
  branchName: String,
  number: String,
  startTime: DateTime
) extends CIBuild

case class S3Project(id: String, name: String) extends Job

object S3Build extends Logging {

  val bucketName = "travis-ci-artifact-test"

  def all(): Seq[CIBuild] =
    for {
      location <- S3Location.all(bucketName) if location.path.endsWith("build.json")
      build <- from(location)
    } yield build

  def from(location: S3Location): Option[S3Build] = (Try {
    parse(S3Location.contents(location))
  } recoverWith  {
    case NonFatal(e) => {
      log.error(s"Error parsing $location", e)
      Failure(e)
    }
  }).toOption

  def parse(json: String): S3Build = {
    import play.api.libs.functional.syntax._

    import utils.Json.DefaultJodaDateReads
    implicit val reads: Reads[S3Build] = (
      (JsPath \ "BuildNumber").read[String].map(_.toLong) and
        (JsPath \ "ProjectName").read[String] and
        (JsPath \ "ProjectName").read[String] and
        (JsPath \ "Branch").read[String] and
        (JsPath \ "BuildNumber").read[String] and
        (JsPath \ "StartTime").read[DateTime]
      )(S3Build.apply _)

    Json.parse(json).as[S3Build]
  }
}
