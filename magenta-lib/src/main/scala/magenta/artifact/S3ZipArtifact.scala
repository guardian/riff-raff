package magenta.artifact

import collection.JavaConverters._
import java.nio.file.{Files, Path}

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.AmazonS3Exception
import magenta.DeployReporter
import magenta.tasks.CommandLine

import scala.util.Try

object S3ZipArtifact {
  def download(artifact: S3JsonArtifact)(implicit client: AmazonS3, reporter: DeployReporter): Path = {
    val dir = Files.createTempDirectory("riffraff-")
    download(artifact, dir)(client, reporter)
    dir
  }

  def download(artifact: S3JsonArtifact, dir: Path)(implicit client: AmazonS3, reporter: DeployReporter) {
    reporter.info("Downloading artifact")

    val path = S3Path(artifact, "artifacts.zip")

    reporter.verbose(s"Downloading from $path to ${dir.toAbsolutePath}...")

    val artifactPath = Files.createTempFile("riffraff-artifact-", ".zip")
    try {
      Files.copy(client.getObject(path.bucket, path.key).getObjectContent, artifactPath)

      CommandLine("unzip" :: "-q" :: "-d" :: dir.toAbsolutePath.toString :: artifactPath.toAbsolutePath.toString :: Nil).run(reporter)

      reporter.verbose("Extracted files")
    } catch {
      case e: AmazonS3Exception if e.getStatusCode == 404 =>
        reporter.fail(s"404 downloading $path\n - have you got the project name and build number correct?")
    } finally {
      Files.delete(artifactPath)
    }
  }

  def delete(artifact: S3JsonArtifact)(implicit client: AmazonS3): Unit = {
    val path = S3Path(artifact, "artifacts.zip")
    client.deleteObject(path.bucket, path.key)
  }

  def withDownload[T](artifact: S3JsonArtifact)(block: Path => T)
    (client: AmazonS3, reporter: DeployReporter): T = {
    val tempDir = Try { download(artifact)(client, reporter) }
    val result = tempDir.map(block)
    // The iterator().asScala stuff can go, once on 2.12
    tempDir.map(dir => Files.walk(dir).iterator().asScala.map(Files.delete(_)))
    result.get
  }
}
