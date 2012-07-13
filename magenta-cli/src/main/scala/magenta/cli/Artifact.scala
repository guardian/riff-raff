package magenta
package cli

import dispatch._
import sbt._
import dispatch.Request.encode_%


object Artifact {

  def download(optProject: Option[String], optBuildNum: Option[String]) = {
    val http = new Http {
      override def make_logger = new Logger {
        def info(msg: String, items: Any*) { MessageBroker.verbose("http: " + msg.format(items:_*)) }
        def warn(msg: String, items: Any*) { MessageBroker.info("http: " + msg.format(items:_*)) }
      }
    }

    val f = for {
      project <- optProject
      buildNum <- optBuildNum
    } yield {

      val tcUrl = :/("teamcity.gudev.gnl", 8111) / "guestAuth" / "repository" / "download" /
        encode_%(project) / buildNum / "artifacts.zip"

      val tmpDir = IO.createTemporaryDirectory

      MessageBroker.verbose("Downloading from %s to %s..." format (tcUrl.to_uri, tmpDir.getAbsolutePath))

      try {
        val files = http(tcUrl >> { IO.unzipStream(_, tmpDir) })
        MessageBroker.verbose("downloaded:\n" + files.mkString("\n"))
      } catch {
        case StatusCode(404, _) =>
          sys.error("404 downloading %s\n - have you got the project name and build number correct?" format tcUrl.to_uri)
      }

      tmpDir
    }

    http.shutdown()

    f getOrElse UsageError("Must supply <project> and <build> (or, for advanced use, --local-artifact)")
  }


}