package magenta.artifact

import magenta.{Build, Loggable}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model._

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

trait S3Location {
  def bucket: String
  def key: String
  def prefixElements: List[String] = key.split("/").toList
  def fileName: String = prefixElements.last
  def extension: Option[String] =
    if (fileName.contains(".")) Some(fileName.split('.').last) else None
  def relativeTo(path: S3Location): String = {
    key.stripPrefix(path.key).stripPrefix("/")
  }
  def fetchContentAsString()(implicit
      client: S3Client
  ): Either[S3Error, String] = S3Location.fetchContentAsString(this)
  def listAll()(implicit client: S3Client): Seq[S3Object] =
    S3Location.listObjects(this)
}

object S3Location extends Loggable {
  def listAll(bucket: String)(implicit s3Client: S3Client): Seq[S3Object] =
    listObjects(bucket, None)

  def listObjects(location: S3Location)(implicit
      s3Client: S3Client
  ): Seq[S3Object] =
    listObjects(location.bucket, Some(location.key))

  val maxKeysInBucketListing =
    1000 // AWS won't return more than this, even if you set the parameter to a larger value

  private def listObjects(bucket: String, prefix: Option[String])(implicit
      s3Client: S3Client
  ): Seq[S3Object] = {
    def request(continuationToken: Option[String]): ListObjectsV2Request =
      ListObjectsV2Request
        .builder()
        .bucket(bucket)
        .prefix(prefix.orNull)
        .maxKeys(maxKeysInBucketListing)
        .continuationToken(continuationToken.orNull)
        .build()

    @tailrec
    def pageListings(
        acc: Seq[ListObjectsV2Response],
        previousListing: ListObjectsV2Response
    ): Seq[ListObjectsV2Response] = {
      if (!previousListing.isTruncated) {
        acc
      } else {
        val listing = s3Client.listObjectsV2(
          request(Some(previousListing.nextContinuationToken))
        )
        pageListings(acc :+ listing, listing)
      }
    }

    val initialListing = s3Client.listObjectsV2(request(None))
    for {
      summaries <- pageListings(Seq(initialListing), initialListing)
      summary <- summaries.contents.asScala
    } yield S3Object(bucket, summary.key, summary.size)
  }

  def fetchContentAsBytes(
      location: S3Location
  )(implicit client: S3Client): Either[S3Error, Array[Byte]] = {
    import cats.syntax.either._
    val getObjRequest = GetObjectRequest
      .builder()
      .bucket(location.bucket)
      .key(location.key)
      .build()
    Either
      .catchNonFatal(client.getObjectAsBytes(getObjRequest).asByteArray())
      .leftMap {
        case e: S3Exception if e.statusCode == 404 => EmptyS3Location(location)
        case e                                     => UnknownS3Error(e)
      }
  }

  def fetchContentAsString(location: S3Location)(implicit client: S3Client): Either[S3Error, String] = {
    fetchContentAsBytes(location).map(bytes => new String(bytes, StandardCharsets.UTF_8))
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

case class S3YamlArtifact(bucket: String, key: String) extends S3Artifact {
  val deployObjectName: String = "riff-raff.yaml"
}

object S3YamlArtifact {
  def apply(build: Build, bucket: String): S3YamlArtifact = {
    val prefix = S3Artifact.buildPrefix(build)
    S3YamlArtifact(bucket, prefix)
  }
}
