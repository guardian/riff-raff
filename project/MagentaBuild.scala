import sbt._
import Keys._
import Defaults._

object MagentaBuild extends Build {
  lazy val root = Project("root", file(".")) aggregate (lib, cli, sbtIo, riffraff)

  // This project contains code extracted from sbt for io handling
  // Once sbt moves to the same version of scala as us, we can use a binary
  // dependency instead
  lazy val sbtIo = magentaProject("sbt-io")

  lazy val lib = magentaProject("magenta-lib") dependsOn(sbtIo)

  lazy val cli = magentaProject("magenta-cli") dependsOn(lib)

  lazy val riffraff = magentaProject("riff-raff") dependsOn(lib)


  val liftVersion = "2.4-M4"

  def magentaProject(name: String) = Project(name, file(name), settings = defaultSettings ++ magentaSettings)

  val magentaSettings: Seq[Setting[_]] = Seq(
    scalaVersion := "2.9.1",
    scalacOptions ++= Seq("-deprecation"),
    version := "1.0"
  )
}