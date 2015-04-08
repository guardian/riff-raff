package magenta.artifact

import java.io.{File, FileOutputStream}
import java.net.URL

import com.amazonaws.auth.{AWSCredentials, AWSCredentialsProvider, DefaultAWSCredentialsProviderChain, AWSCredentialsProviderChain}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import com.amazonaws.services.s3.model.AmazonS3Exception
import dispatch.classic.Request._
import dispatch.classic.{StatusCode, _}
import magenta.{Build, MessageBroker}
import magenta.tasks.CommandLine

import scala.io.Source
import scala.util.Try
import scalax.file.ImplicitConversions.defaultPath2jfile
import scalax.file.Path
import scalax.io.{ScalaIOException, Resource}

object S3Artifact {

  lazy val client = new AmazonS3Client()

  def download(build: Build)(implicit bucket: Option[String], client: AmazonS3): File = {
    val dir = Path.createTempDirectory(prefix="riffraff-", suffix="")
    download(build, dir)(bucket, client)
    dir
  }

  def download(build: Build, dir: File)(implicit bucket: Option[String], client: AmazonS3) {
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
      case e: ScalaIOException => e.getCause match {
        case e: AmazonS3Exception if e.getStatusCode == 404 =>
          MessageBroker.fail(s"404 downloading s3://${bucket.get}/$path\n - have you got the project name and build number correct?")
      }
    }
  }

  def withDownload[T](build: Build)(block: File => T)(implicit bucket: Option[String], client: AmazonS3): T = {
    val tempDir = Try { download(build)(bucket, client) }
    val result = tempDir.map(block)
    tempDir.map(dir => Path(dir).deleteRecursively(continueOnFailure = true))
    result.get
  }
}
