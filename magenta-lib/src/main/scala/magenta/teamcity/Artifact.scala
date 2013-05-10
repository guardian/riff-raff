package magenta.teamcity

import java.io.File
import dispatch.classic.{StatusCode, :/, Logger, Http}
import magenta.{Build, MessageBroker}
import dispatch.classic.Request._
import scalax.file.Path
import scalax.file.ImplicitConversions.defaultPath2jfile
import scala.util.Try
import java.util.zip.ZipInputStream
import scalax.io.Resource
import scalax.io.JavaConverters._
import scala.annotation.tailrec

object Artifact {

  def download(build: Build): File = {
    val dir = Path.createTempDirectory()
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

    val tcUrl = :/("teamcity.gudev.gnl", 8111) / "guestAuth" / "repository" / "download" /
        encode_%(build.projectName) / build.id / "artifacts.zip"

    MessageBroker.verbose("Downloading from %s to %s..." format (tcUrl.to_uri, dir.getAbsolutePath))

    try {
      http(tcUrl >> { is =>
        val zip = new ZipInputStream(is)

        @tailrec
        def next() {
          val entry = zip.getNextEntry()
          if(entry != null) {
            val name = entry.getName
            val target = new File(dir, name)
            if(entry.isDirectory)
              Path(target).createDirectory()
            else
              Resource.fromFile(target).doCopyFrom(zip.asUnmanagedInput)
            target.setLastModified(entry.getTime)
            zip.closeEntry()
            next()
          }
        }
        next()
        zip.close()
      })
      MessageBroker.verbose("Extracted files")
    } catch {
      case StatusCode(404, _) =>
        MessageBroker.fail("404 downloading %s\n - have you got the project name and build number correct?" format tcUrl.to_uri)
    }

    http.shutdown()
  }

  def withDownload[T](build: Build)(block: File => T): T = {
    val tempDir = Path.createTempDirectory(prefix="riffraff-", suffix="")
    val result = Try {
      download(tempDir, build)
      block(tempDir)
    }
    tempDir.deleteRecursively(continueOnFailure = true)
    result.get
  }
}
