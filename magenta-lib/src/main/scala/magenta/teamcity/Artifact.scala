package magenta.teamcity

import java.io.{FileOutputStream, File}
import dispatch.classic.{StatusCode, :/, Logger, Http}
import magenta.{Build, MessageBroker}
import dispatch.classic.Request._
import scalax.file.Path
import scalax.file.ImplicitConversions.defaultPath2jfile
import scala.util.Try
import magenta.tasks.CommandLine

object Artifact {

  def download(build: Build): File = {
    val dir = Path.createTempDirectory(prefix="riffraff-", suffix="")
    download(dir, build)
    dir
  }

  def download(dir: File, build: Build) {
    MessageBroker.info("Downloading artifact")
    val http = new Http {
      override def make_logger = new Logger {
        def info(msg: String, items: Any*) { MessageBroker.verbose("http: " + msg.format(items:_*)) }
        def warn(msg: String, items: Any*) { MessageBroker.info("http: " + msg.format(items:_*)) }
      }
    }

    val tcUrl = :/("teamcity.guprod.gnm", 80) / "guestAuth" / "repository" / "download" /
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

  def withDownload[T](build: Build)(block: File => T): T = {
    val tempDir = Try { download(build) }
    val result = tempDir.map(block)
    tempDir.map(dir => Path(dir).deleteRecursively(continueOnFailure = true))
    result.get
  }
}
