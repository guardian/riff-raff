package magenta.artifact

import java.io.File

import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import magenta.tasks.CommandLine
import magenta.{Build, DeployReporter}

import scala.util.Try
import scalax.file.ImplicitConversions.defaultPath2jfile
import scalax.file.Path
import scalax.io.{Resource, ScalaIOException}

object S3ZipArtifact {
  def download(artifact: S3Artifact)(implicit client: AmazonS3, reporter: DeployReporter): File = {
    val dir = Path.createTempDirectory(prefix="riffraff-", suffix="")
    download(artifact, dir)(client, reporter)
    dir
  }

  def download(artifact: S3Artifact, dir: File)(implicit client: AmazonS3, reporter: DeployReporter) {
    reporter.info("Downloading artifact")

    val path = s"${artifact.key}/artifacts.zip"

    reporter.verbose(s"Downloading from $path to ${dir.getAbsolutePath}...")

    val artifactPath = Path.createTempFile(prefix = "riffraff-artifact-", suffix = ".zip")
    try {
      val blob = Resource.fromInputStream(client.getObject(artifact.bucket, path).getObjectContent)
      blob.copyDataTo(artifactPath)

      CommandLine("unzip" :: "-q" :: "-d" :: dir.getAbsolutePath :: artifactPath.getAbsolutePath :: Nil).run(reporter)

      reporter.verbose("Extracted files")
    } catch {
      case e: ScalaIOException => e.getCause match {
        case e: AmazonS3Exception if e.getStatusCode == 404 =>
          reporter.fail(s"404 downloading s3://${artifact.bucket}/$path\n - have you got the project name and build number correct?")
      }
    } finally {
      artifactPath.delete()
    }
  }

  def withDownload[T](artifact: S3Artifact)(block: File => T)
    (client: AmazonS3, reporter: DeployReporter): T = {
    val tempDir = Try { download(artifact)(client, reporter) }
    val result = tempDir.map(block)
    tempDir.map(dir => Path(dir).deleteRecursively(continueOnFailure = true))
    result.get
  }
}
