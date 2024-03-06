package ci

import conf.Config
import controllers.Logging
import magenta.artifact.{S3Error, S3Location, S3Object}
import org.joda.time.DateTime
import play.api.libs.json._

import scala.util.Try
import scala.util.control.NonFatal
import cats.syntax.either._
import play.api.MarkerContext

import scala.jdk.CollectionConverters._
import net.logstash.logback.marker.Markers.appendEntries
import utils.VCSInfo

import scala.collection.Seq

case class S3Build(
    id: Long,
    jobName: String,
    jobId: String,
    branchName: String,
    number: String,
    startTime: DateTime,
    revision: String,
    vcsURL: String,
    buildTool: Option[String]
) extends CIBuild

case class S3Project(id: String, name: String) extends Job

class S3BuildOps(config: Config) extends Logging {

  lazy val bucketName = config.build.aws.bucketName
  implicit lazy val client = config.build.aws.client

  def buildJsons: Seq[S3Object] = Try {
    S3Location.listAll(bucketName).filter(_.key.endsWith("build.json"))
  } recover { case NonFatal(e) =>
    log.error(s"Error finding buildJsons", e)
    Nil
  } get

  private def buildMarker(build: S3Build): MarkerContext = {
    val params: Map[String, String] = Map(
      "projectName" -> build.jobName,
      "projectBuildNumber" -> build.number,
      "projectBuildTool" -> build.buildTool.getOrElse("unknown"),
      "projectVcsUrl" -> VCSInfo.normalise(build.vcsURL).getOrElse("unknown"),
      "projectVcsBranch" -> build.branchName,
      "projectVcsRevision" -> build.revision
    )
    MarkerContext(appendEntries(params.asJava))
  }

  def buildAt(location: S3Object): Either[S3BuildError, S3Build] =
    location
      .fetchContentAsString()
      .leftMap[S3BuildError](S3BuildRetrievalError)
      .flatMap { s =>
        val s3BuildEither = parse(s)

        s3BuildEither match {
          case Left(err) => {
            log.warn(
              s"Error parsing JSON from $location, error: $err"
            )
          }
          case Right(s3Build) => {
            log.info(s"Successfully parsed JSON from $location")(
              buildMarker(s3Build)
            )
          }
        }

        s3BuildEither.leftMap[S3BuildError](S3BuildParseError)
      }

  def parse(
      json: String
  ): Either[Seq[(JsPath, Seq[JsonValidationError])], S3Build] = {
    import play.api.libs.functional.syntax._
    import utils.Json.DefaultJodaDateReads
    import cats.syntax.either._
    implicit val reads: Reads[S3Build] = (
      (JsPath \ "buildNumber")
        .read[String]
        .flatMap(s =>
          Reads(_ =>
            Either.catchOnly[NumberFormatException](s.toLong) match {
              case Left(e)  => JsError(s"'$s' is not a number")
              case Right(l) => JsSuccess(l)
            }
          )
        ) and
        (JsPath \ "projectName").read[String] and
        (JsPath \ "projectName").read[String] and
        (JsPath \ "branch").read[String] and
        (JsPath \ "buildNumber").read[String] and
        (JsPath \ "startTime").read[DateTime] and
        (JsPath \ "revision").read[String] and
        (JsPath \ "vcsURL").read[String] and
        (JsPath \ "buildTool").readNullable[String]
    )(S3Build.apply _)

    Json.parse(json).validate[S3Build].asEither
  }
}

sealed trait S3BuildError
final case class S3BuildRetrievalError(s3Error: S3Error) extends S3BuildError
final case class S3BuildParseError(
    parseErrors: Seq[(JsPath, Seq[JsonValidationError])]
) extends S3BuildError
