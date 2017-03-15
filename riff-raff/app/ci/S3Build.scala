package ci

import conf.Configuration
import controllers.Logging
import magenta.artifact.{S3Error, S3Location, S3Object}
import org.joda.time.DateTime
import play.api.data.validation.ValidationError
import play.api.libs.json._

import scala.util.Try
import scala.util.control.NonFatal
import cats.syntax.either._

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

  def buildJsons: Seq[S3Object] = Try {
    S3Location.listAll(bucketName).filter(_.key.endsWith("build.json"))
  } recover {
    case NonFatal(e) =>
      log.error(s"Error finding buildJsons", e)
      Nil
  } get

  def buildAt(location: S3Object): Either[S3BuildError, S3Build] =
    location.fetchContentAsString().leftMap[S3BuildError](S3BuildRetrievalError(_))
      .flatMap(s => parse(s).leftMap[S3BuildError](S3BuildParseError(_)))

  def parse(json: String): Either[Seq[(JsPath, Seq[ValidationError])], S3Build] = {
    import play.api.libs.functional.syntax._
    import utils.Json.DefaultJodaDateReads
    import cats.syntax.either._
    implicit val reads: Reads[S3Build] = (
      (JsPath \ "buildNumber").read[String].flatMap(s => Reads(_ =>
        Either.catchOnly[NumberFormatException](s.toLong) match {
          case Left(e) => JsError(s"'$s' is not a number")
          case Right(l) => JsSuccess(l)
        }
      )) and
        (JsPath \ "projectName").read[String] and
        (JsPath \ "projectName").read[String] and
        (JsPath \ "branch").read[String] and
        (JsPath \ "buildNumber").read[String] and
        (JsPath \ "startTime").read[DateTime] and
        (JsPath \ "revision").read[String] and
        (JsPath \ "vcsURL").read[String]
      )(S3Build.apply _)

    Json.parse(json).validate[S3Build].asEither
  }
}

sealed trait S3BuildError
final case class S3BuildRetrievalError(s3Error: S3Error) extends S3BuildError
final case class S3BuildParseError(parseErrors: Seq[(JsPath, Seq[ValidationError])]) extends S3BuildError