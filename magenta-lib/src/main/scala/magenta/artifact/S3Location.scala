package magenta.artifact

import java.io.File

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.gu.management.Loggable
import magenta.{Build, DeployReporter}

trait S3Location {
  def bucket: String
  def key: String
  def prefixElements: List[String] = key.split("/").toList
  def fileName:String = prefixElements.last
  def extension:Option[String] = if (fileName.contains(".")) Some(fileName.split('.').last) else None
  def string()(implicit client:AmazonS3):Option[String] = {
    try {
      Some(client.getObjectAsString(bucket, key))
    } catch {
      case e:AmazonS3Exception if e.getErrorCode == "NoSuchKey" => None
    }
  }
  def relativeTo(path: S3Location): String = {
    key.stripPrefix(path.key).stripPrefix("/")
  }
}

case class S3Path(bucket: String, key: String) extends S3Location

object S3Path {
  def apply(location: S3Location, key: String): S3Path = S3Path(location.bucket, s"${location.key}/$key")
}

case class S3Object(bucket: String, key: String, size: Long) extends S3Location

object S3Artifact extends Loggable {
  def apply(build: Build, bucket: String): S3Artifact = {
    val prefix = buildPrefix(build)
    S3Artifact(bucket, prefix)
  }

  def buildPrefix(build: Build): String = {
    s"${build.projectName}/${build.id}"
  }

  /*
  This is essentially the same as the apply method in function, but in this case we actually confirm that the
  deployObject exists. If it doesn't then we try to convert a legacy artifacts.zip file into the new format.
   */
  def withLegacyFallback(build: Build, bucket: String)(implicit client: AmazonS3, reporter: DeployReporter): S3Artifact = {
    val artifact = S3Artifact(build, bucket)
    try {
      val metadata = client.getObjectMetadata(artifact.bucket, s"${artifact.key}/${artifact.deployObject}")
      logger.debug(s"Verified format package exists: $metadata")
      artifact
    } catch {
      case e:AmazonS3Exception if e.getStatusCode == 404 => convertFromLegacy(build, bucket)
    }
  }

  def convertFromLegacy(build:Build, bucket: String)(implicit client: AmazonS3, reporter: DeployReporter): S3Artifact = {
    reporter.info("Converting legacy artifact.zip to new S3 layout")
    implicit val sourceBucket: Option[String] = Some(bucket)
    S3LegacyArtifact.withDownload(build){ dir =>
      // use our deployment task to upload the downloaded artifact to S3
      val prefix = buildPrefix(build)
      val filesToUpload = resolveFiles(dir, prefix)
      reporter.info(s"Uploading contents of artifact (${filesToUpload.size} files) to S3")
      filesToUpload.foreach{ case (file, key) =>
          client.putObject(bucket, key, file)
      }
      reporter.info(s"Legacy artifact converted")
      S3Artifact(bucket, prefix)
    }
    // TODO: Delete the legacy format (not yet as we might need to rollback)
  }

  private def subDirectoryPrefix(key: String, file:File): String = if (key.isEmpty) file.getName else s"$key/${file.getName}"
  private def resolveFiles(file: File, key: String): Seq[(File, String)] = {
    if (!file.isDirectory) Seq((file, key))
    else file.listFiles.toSeq.flatMap(f => resolveFiles(f, subDirectoryPrefix(key, f))).distinct
  }
}

case class S3Artifact(bucket: String, key: String, deployObject: String = "deploy.json") extends S3Location {
  def getPackage(packageName: String): S3Package = S3Package(bucket, s"$key/packages/$packageName")
}

case class S3Package(bucket: String, key: String) extends S3Location