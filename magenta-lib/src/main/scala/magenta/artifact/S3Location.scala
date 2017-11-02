package magenta.artifact

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model._
import com.gu.management.Loggable
import magenta.Build
import play.api.libs.json.{JsonValidationError, JsPath}

import scala.annotation.tailrec
import scala.collection.JavaConverters._

trait S3Location {
  def bucket: String
  def key: String
  def prefixElements: List[String] = key.split("/").toList
  def fileName:String = prefixElements.last
  def extension:Option[String] = if (fileName.contains(".")) Some(fileName.split('.').last) else None
  def relativeTo(path: S3Location): String = {
    key.stripPrefix(path.key).stripPrefix("/")
  }
  def fetchContentAsString()(implicit client: AmazonS3) = S3Location.fetchContentAsString(this)
  def listAll()(implicit client: AmazonS3) = S3Location.listObjects(this)
}

object S3Location extends Loggable {
  def listAll(bucket: String)(implicit s3Client: AmazonS3): Seq[S3Object] = listObjects(bucket, None)

  def listObjects(location: S3Location)(implicit s3Client: AmazonS3): Seq[S3Object] =
    listObjects(location.bucket, Some(location.key))

  val maxKeysInBucketListing = 1000 // AWS won't return more than this, even if you set the parameter to a larger value

  private def listObjects(bucket: String, prefix: Option[String])(implicit s3Client: AmazonS3): Seq[S3Object] = {
    def request(continuationToken: Option[String]) = new ListObjectsV2Request()
      .withBucketName(bucket)
      .withPrefix(prefix.orNull)
      .withMaxKeys(maxKeysInBucketListing)
      .withContinuationToken(continuationToken.orNull)

    @tailrec
    def pageListings(acc: Seq[ListObjectsV2Result], previousListing: ListObjectsV2Result): Seq[ListObjectsV2Result] = {
      if (!previousListing.isTruncated) {
        acc
      } else {
        val listing = s3Client.listObjectsV2(
          request(Some(previousListing.getNextContinuationToken))
        )
        pageListings(acc :+ listing, listing)
      }
    }

    val initialListing = s3Client.listObjectsV2(request(None))
    for {
      summaries <- pageListings(Seq(initialListing), initialListing)
      summary <- summaries.getObjectSummaries.asScala
    } yield S3Object(summary.getBucketName, summary.getKey, summary.getSize)
  }

  def fetchContentAsString(location: S3Location)(implicit client:AmazonS3): Either[S3Error, String] = {
    import cats.syntax.either._
    Either.catchNonFatal(client.getObjectAsString(location.bucket, location.key)).leftMap {
      case e: AmazonS3Exception if e.getStatusCode == 404 => EmptyS3Location(location)
      case e => UnknownS3Error(e)
    }
  }
}

sealed trait S3Error
case class EmptyS3Location(location: S3Location) extends S3Error
case class UnknownS3Error(exception: Throwable) extends S3Error

case class S3Path(bucket: String, key: String) extends S3Location {
  def show(): String = s"Bucket: '$bucket', Key: '$key'"
}

object S3Path {
  def apply(location: S3Location, key: String): S3Path = {
    val delimiter = if (location.key.endsWith("/")) "" else "/"
    S3Path(location.bucket, s"${location.key}$delimiter$key")
  }
}

case class S3Object(bucket: String, key: String, size: Long) extends S3Location

trait S3Artifact extends S3Location {
  def deployObjectName: String
  def deployObject = S3Path(this, deployObjectName)
}

object S3Artifact {
  def buildPrefix(build: Build): String = {
    s"${build.projectName}/${build.id}/"
  }
}

sealed abstract class ArtifactResolutionError
case class S3ArtifactError(underlying: S3Error) extends ArtifactResolutionError
case class JsonArtifactError(underlying: Seq[(JsPath, Seq[JsonValidationError])]) extends ArtifactResolutionError

case class S3YamlArtifact(bucket: String, key: String) extends S3Artifact {
  val deployObjectName: String = "riff-raff.yaml"
}

object S3YamlArtifact {
  def apply(build: Build, bucket: String): S3YamlArtifact = {
    val prefix = S3Artifact.buildPrefix(build)
    S3YamlArtifact(bucket, prefix)
  }
}
