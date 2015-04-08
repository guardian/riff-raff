package magenta.artifact

import java.io.{File, FileOutputStream}
import java.net.URL

import com.amazonaws.services.s3.AmazonS3Client
import dispatch.classic.Request._
import dispatch.classic.{StatusCode, _}
import magenta.{Build, MessageBroker}
import magenta.tasks.CommandLine

import scala.io.Source
import scala.util.Try
import scalax.file.ImplicitConversions.defaultPath2jfile
import scalax.file.Path
import scalax.io.Resource

object S3Artifact {

  val client = new AmazonS3Client()

  def download(bucket: Option[String], build: Build): File = {
    val dir = Path.createTempDirectory(prefix="riffraff-", suffix="")
    download(bucket, dir, build)
    dir
  }

  def download(bucket: Option[String], dir: File, build: Build) {
    MessageBroker.info("Downloading artifact")

    if (bucket.isEmpty) MessageBroker.fail("Don't know where to get artifact - no bucket set")

    val path = s"${build.projectName}/${build.id}/artifacts/artifacts.zip"

    MessageBroker.verbose(s"Downloading from $path to ${dir.getAbsolutePath}...")

    try {
      val artifactPath = Path.createTempFile(prefix = "riffraff-artifact-", suffix = ".zip")

      val blob = Resource.fromInputStream(client.getObject(bucket.get, path).getObjectContent)

      artifactPath.write(blob.bytes)

      CommandLine("unzip" :: "-q" :: "-d" :: dir.getAbsolutePath :: artifactPath.getAbsolutePath :: Nil).run()
      artifactPath.delete()
      MessageBroker.verbose("Extracted files")
    } catch {
      case StatusCode(404, _) =>
        MessageBroker.fail(s"404 downloading s3://${bucket.get}/$path\n - have you got the project name and build number correct?")
    }
  }

  def withDownload[T](bucket: Option[String], build: Build)(block: File => T): T = {
    val tempDir = Try { download(bucket, build) }
    val result = tempDir.map(block)
    tempDir.map(dir => Path(dir).deleteRecursively(continueOnFailure = true))
    result.get
  }
}
