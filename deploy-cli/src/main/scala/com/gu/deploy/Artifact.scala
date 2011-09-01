package com.gu.deploy

import java.io.File
import dispatch._


object Artifact {

  def download(optProject: Option[String], optBuildNum: Option[String]) = {
    val f = for {
      project <- optProject
      buildNum <- optBuildNum
    } yield {

      val tcUrl = :/("teamcity.gudev.gnl", 8111) / "guestAuth" / "repository" / "download" /
        project / buildNum / "artifact.zip"

      Log.verbose("Downloading from %s..." format tcUrl.to_uri)

      new File("/tmp")
    }

    f getOrElse sys.error("Must supply <project> and <build> (or, for advanced use, --local-artifact)")
  }
}