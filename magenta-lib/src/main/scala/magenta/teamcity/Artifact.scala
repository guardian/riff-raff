package magenta.teamcity

import java.io.{FileOutputStream, File}
import dispatch.classic._
import magenta.{Build, MessageBroker}
import dispatch.classic.Request._
import scalax.file.Path
import scalax.file.ImplicitConversions.defaultPath2jfile
import scala.util.Try
import magenta.tasks.CommandLine
import java.net.URL
import dispatch.classic.StatusCode
import magenta.Build

object Artifact {

  def download(teamcity: Option[URL], build: Build): File = {
    val dir = Path.createTempDirectory(prefix="riffraff-", suffix="")
    download(teamcity, dir, build)
    dir
  }

  def download(teamcity: Option[URL], dir: File, build: Build) {
    MessageBroker.info("Downloading artifact")
    val http = new Http {
      override def make_logger = new Logger {
        def info(msg: String, items: Any*) { MessageBroker.verbose("http: " + msg.format(items:_*)) }
        def warn(msg: String, items: Any*) { MessageBroker.info("http: " + msg.format(items:_*)) }
      }
    }

    if (teamcity.isEmpty) MessageBroker.fail("Don't know where to get artifact - no teamcity URL set")

    val tcUrl = url(teamcity.get.toString) / "guestAuth" / "repository" / "download" /
        encode_%(build.projectName) / build.id / "artifacts.zip"

    MessageBroker.verbose("Downloading from %s to %s..." format (tcUrl.to_uri, dir.getAbsolutePath))

    try {
      val artifact = Path.createTempFile(prefix = "riffraff-artifact-", suffix = ".zip")
      http(tcUrl >>> new FileOutputStream(artifact))
      CommandLine("unzip" :: "-q" :: "-d" :: dir.getAbsolutePath :: artifact.getAbsolutePath :: Nil).run()
      artifact.delete()
      MessageBroker.verbose("Extracted files")
    } catch {
      case StatusCode(404, _) =>
        MessageBroker.fail("404 downloading %s\n - have you got the project name and build number correct?" format tcUrl.to_uri)
    }

    http.shutdown()
  }

  def withDownload[T](teamcity: Option[URL], build: Build)(block: File => T): T = {
    val tempDir = Try { download(teamcity, build) }
    val result = tempDir.map(block)
    tempDir.map(dir => Path(dir).deleteRecursively(continueOnFailure = true))
    result.get
  }
}
