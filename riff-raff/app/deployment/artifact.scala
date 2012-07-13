package deployment

import dispatch._
import sbt._
import dispatch.Request.encode_%
import magenta.json.DeployInfoJsonReader
import magenta.{MessageBroker, Build}

object Artifact {

  def download(build: Build) = {
    val http = new Http {
      override def make_logger = new Logger {
        def info(msg: String, items: Any*) { MessageBroker.verbose("http: " + msg.format(items: _*)) }
        def warn(msg: String, items: Any*) { MessageBroker.info("http: " + msg.format(items: _*)) }
      }
    }

    val tcUrl = :/("teamcity.gudev.gnl", 8111) / "guestAuth" / "repository" / "download" /
      encode_%(build.name) / build.id / "artifacts.zip"

    val tmpDir = IO.createTemporaryDirectory

    MessageBroker.verbose("Downloading from %s to %s..." format (tcUrl.to_uri, tmpDir.getAbsolutePath))

    try {
      val files = http(tcUrl >> { IO.unzipStream(_, tmpDir) })
      MessageBroker.verbose("downloaded:\n" + files.mkString("\n"))
    } catch {
      case StatusCode(404, _) =>
        MessageBroker.fail("404 downloading %s\n - have you got the project name and build number correct?" format tcUrl.to_uri)
    }

    http.shutdown()

    tmpDir
  }

}

object DeployInfo {
  lazy val hostList = {
    import sys.process._
    DeployInfoJsonReader.parse("/opt/bin/deployinfo.json".!!)
  }
}
