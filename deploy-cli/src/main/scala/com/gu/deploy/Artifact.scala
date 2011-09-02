package com.gu.deploy

import java.io.File
import dispatch._
import sbt._


object Artifact {

  def download(optProject: Option[String], optBuildNum: Option[String]) = {
    val f = for {
      project <- optProject
      buildNum <- optBuildNum
    } yield {

      val tcUrl = :/("teamcity.gudev.gnl", 8111) / "guestAuth" / "repository" / "download" /
        project / buildNum / "artifacts.zip"

      val tmpDir = IO.createTemporaryDirectory

      Log.verbose("Downloading from %s to %s..." format (tcUrl.to_uri, tmpDir.getAbsolutePath))

      val files = Http(tcUrl >> { IO.unzipStream(_, tmpDir) })

      Log.verbose("downloaded:\n" + files.mkString("\n"))

      tmpDir
    }

    f getOrElse sys.error("Must supply <project> and <build> (or, for advanced use, --local-artifact)")
  }


}