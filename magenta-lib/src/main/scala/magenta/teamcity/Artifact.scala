package magenta.teamcity

import java.io.File
import dispatch.{StatusCode, :/, Logger, Http}
import magenta.{Build, MessageBroker}
import dispatch.Request._
import sbt.IO

object Artifact {

  implicit def build2download(build:Build) = new {
    def download(): File = {
      val dir = IO.createTemporaryDirectory
      download(dir)
      dir
    }

    def download(dir: File) {
      MessageBroker.info("Downloading artifact")
      val http = new Http {
        override def make_logger = new Logger {
          def info(msg: String, items: Any*) { MessageBroker.verbose("http: " + msg.format(items:_*)) }
          def warn(msg: String, items: Any*) { MessageBroker.info("http: " + msg.format(items:_*)) }
        }
      }

      val tcUrl = :/("teamcity.gudev.gnl", 8111) / "guestAuth" / "repository" / "download" /
          encode_%(build.name) / build.id / "artifacts.zip"

      MessageBroker.verbose("Downloading from %s to %s..." format (tcUrl.to_uri, dir.getAbsolutePath))

      try {
        val files = http(tcUrl >> { IO.unzipStream(_, dir) })
        MessageBroker.verbose("downloaded:\n" + files.mkString("\n"))
      } catch {
        case StatusCode(404, _) =>
          MessageBroker.fail("404 downloading %s\n - have you got the project name and build number correct?" format tcUrl.to_uri)
      }

      http.shutdown()
    }

    def withDownload[T](block: File => T): T = {
      IO.withTemporaryDirectory{ tempDir =>
        download(tempDir)
        block(tempDir)
      }
    }
  }

}
