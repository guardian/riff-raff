package magenta.artifact

import java.io.File

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model._
import com.gu.management.Loggable
import dispatch.classic./
import magenta.json.JsonInputFile
import magenta.{Build, DeployReporter}
import play.api.libs.json.{JsPath, JsonValidationError}

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

case class S3Path(bucket: String, key: String) extends S3Location

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

case class S3JsonArtifact(bucket: String, key: String) extends S3Artifact {
  val deployObjectName: String = "deploy.json"
}

object S3JsonArtifact extends Loggable {
  def apply(build: Build, bucket: String): S3JsonArtifact = {
    val prefix = S3Artifact.buildPrefix(build)
    S3JsonArtifact(bucket, prefix)
  }

  def fetchInputFile(artifact: S3JsonArtifact, deprecatedPause: Option[Int])(
    implicit client: AmazonS3, reporter: DeployReporter): Either[ArtifactResolutionError, JsonInputFile] = {

    import cats.syntax.either._
    val possibleJson = (artifact.deployObject.fetchContentAsString()(client)).orElse {
      convertFromZipBundle(artifact, deprecatedPause)
      artifact.deployObject.fetchContentAsString()(client)
    }
    possibleJson.leftMap[ArtifactResolutionError](S3ArtifactError(_)).flatMap(s =>
      JsonInputFile.parse(s).leftMap(JsonArtifactError(_))
    )
  }

  def convertFromZipBundle(artifact: S3JsonArtifact, deprecatedPause: Option[Int])(implicit client: AmazonS3, reporter: DeployReporter): Unit = {
    reporter.warning(
      """DEPRECATED: The artifact.zip is now a legacy format - please switch to the new format (if you
        |are using sbt-riffraff-artifact then simply upgrade to >= 0.9.4, if you use the TeamCity upload plugin
        |you'll need to use the riffRaffNotifyTeamcity task instead of the riffRaffArtifact task). NOTE: Support will
        |be removed at the end of September 2017.""".stripMargin)
    deprecatedPause.foreach { pause =>
      reporter.warning(
        s"To persuade you to migrate we will now\npause this deploy for $pause seconds whilst you reflect on your ways.")
      Thread.sleep(pause * 1000)
    }
    reporter.info("Converting artifact.zip to S3 layout")
    implicit val sourceBucket: Option[String] = Some(artifact.bucket)
    S3ZipArtifact.withDownload(artifact){ dir =>
      val filesToUpload = resolveFiles(dir.toFile, artifact.key)
      reporter.info(s"Uploading contents of artifact (${filesToUpload.size} files) to S3")
      filesToUpload.foreach{ case (file, key) =>
        val metadata = new ObjectMetadata()
        metadata.setContentLength(file.length)
        val req = new PutObjectRequest(artifact.bucket, key, file).withMetadata(metadata)
        client.putObject(req)
      }
      reporter.info(s"Zip artifact converted")
    }(client, reporter)

    S3ZipArtifact.delete(artifact)
    reporter.verbose("Zip artifact deleted")
  }

  private def subDirectoryPrefix(key: String, file:File): String = {
    if (key.isEmpty) {
      file.getName
    } else {
      val delimiter = if (key.endsWith("/")) "" else "/"
      s"$key$delimiter${file.getName}"
    }
  }
  private def resolveFiles(file: File, key: String): Seq[(File, String)] = {
    if (!file.isDirectory) Seq((file, key))
    else file.listFiles.toSeq.flatMap(f => resolveFiles(f, subDirectoryPrefix(key, f))).distinct
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
