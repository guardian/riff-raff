import sbt._
import Keys._
import Defaults._

object DeployBuild extends Build {
  lazy val root = Project("root", file(".")) aggregate (lib, cli)

  lazy val lib = deployProject("deploy-lib")

  lazy val cli = deployProject("deploy-cli") dependsOn(lib)


  def deployProject(name: String) = Project(name, file(name), settings = defaultSettings ++ deploySettings)

  val deploySettings: Seq[Setting[_]] = Seq(
    scalaVersion := "2.9.0-1"
  )
}