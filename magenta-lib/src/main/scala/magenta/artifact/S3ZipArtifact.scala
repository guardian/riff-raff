package magenta.artifact

import java.io.File

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.AmazonS3Exception
import magenta.DeployReporter
import magenta.tasks.CommandLine

import scala.util.Try
import scalax.file.ImplicitConversions.defaultPath2jfile
import scalax.file.Path
import scalax.io.{Resource, ScalaIOException}

object S3ZipArtifact {
  def download(artifact: S3JsonArtifact)(implicit client: AmazonS3, reporter: DeployReporter): File = {
    val dir = Path.createTempDirectory(prefix="riffraff-", suffix="")
    download(artifact, dir)(client, reporter)
    dir
  }

  def download(artifact: S3JsonArtifact, dir: File)(implicit client: AmazonS3, reporter: DeployReporter) {
    reporter.info("Downloading artifact")

    val path = S3Path(artifact, "artifacts.zip")

    reporter.verbose(s"Downloading from $path to ${dir.getAbsolutePath}...")

    val artifactPath = Path.createTempFile(prefix = "riffraff-artifact-", suffix = ".zip")
    try {
      val blob = Resource.fromInputStream(client.getObject(path.bucket, path.key).getObjectContent)
      blob.copyDataTo(artifactPath)

      CommandLine("unzip" :: "-q" :: "-d" :: dir.getAbsolutePath :: artifactPath.getAbsolutePath :: Nil).run(reporter)

      reporter.verbose("Extracted files")
    } catch {
      case e: ScalaIOException => e.getCause match {
        case e: AmazonS3Exception if e.getStatusCode == 404 =>
          reporter.fail(s"404 downloading $path\n - have you got the project name and build number correct?")
      }
    } finally {
      artifactPath.delete()
    }
  }

  def delete(artifact: S3JsonArtifact)(implicit client: AmazonS3): Unit = {
    val path = S3Path(artifact, "artifacts.zip")
    client.deleteObject(path.bucket, path.key)
  }

  def withDownload[T](artifact: S3JsonArtifact)(block: File => T)
    (client: AmazonS3, reporter: DeployReporter): T = {
    val tempDir = Try { download(artifact)(client, reporter) }
    val result = tempDir.map(block)
    tempDir.map(dir => Path(dir).deleteRecursively(continueOnFailure = true))
    result.get
  }
}
