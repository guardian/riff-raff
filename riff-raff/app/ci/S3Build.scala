package ci

import ci.teamcity.Job
import controllers.Logging
import org.joda.time.DateTime
import play.api.libs.json.{JsPath, Json, Reads}
import rx.lang.scala.Observable
import rx.lang.scala.schedulers.{NewThreadScheduler, IOScheduler, ComputationScheduler}

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

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

  def buildJsons: Seq[S3Location] =
    S3Location.all(bucketName).filter(_.path.endsWith("build.json"))

  def buildAt(location: S3Location): Option[S3Build] = (Try {
    log.info(s"Parsing ${location.path}")
    val build = parse(S3Location.contents(location))
    log.info(s"Parsed ${location.path}")
    build
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
