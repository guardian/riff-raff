import sbt._
import Keys._
import Defaults._

object MagentaBuild extends Build {
  lazy val root = Project("root", file(".")) aggregate (lib, cli, sbtIo)

  // This project contains code extracted from sbt for io handling
  // Once sbt moves to the same version of scala as us, we can use a binary
  // dependency instead
  lazy val sbtIo = deployProject("sbt-io")

  lazy val lib = deployProject("magenta-lib")

  lazy val cli = deployProject("magenta-cli") dependsOn(lib, sbtIo)


  def deployProject(name: String) = Project(name, file(name), settings = defaultSettings ++ magentaSettings)

  val magentaSettings: Seq[Setting[_]] = Seq(
    scalaVersion := "2.9.0-1"
  )
}