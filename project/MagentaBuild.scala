import sbt._
import Keys._
import play.twirl.sbt.Import._
import com.typesafe.sbt.web.SbtWeb
import com.gu.riffraff.artifact.RiffRaffArtifact
import com.typesafe.sbt.packager.universal.UniversalPlugin
import sbtbuildinfo.{BuildInfoOption, BuildInfoPlugin}
import sbtbuildinfo.BuildInfoPlugin.BuildInfoKey
import sbtbuildinfo.BuildInfoKeys._

object MagentaBuild extends Build {
  lazy val root = Project("root", file(".")) aggregate (lib, cli, riffraff)

  lazy val lib = magentaProject("magenta-lib")

  lazy val cli = magentaProject("magenta-cli") dependsOn(lib)

  lazy val riffraff = magentaPlayProject("riff-raff", "riffraff") dependsOn(lib)

  val guardianManagementVersion = "5.35"
  val guardianManagementPlayVersion = "7.2"

  def magentaProject(name: String) = Project(name, file(name)).settings(magentaSettings: _*)

  def magentaPlayProject(projectName: String, versionPackage: String) = Project(projectName, file(projectName))
    .enablePlugins(play.PlayScala, SbtWeb, RiffRaffArtifact, UniversalPlugin, BuildInfoPlugin)
    .settings( magentaSettings: _* )
    .settings(
      testOptions in Test := Nil,
      TwirlKeys.templateImports ++= Seq(
        "magenta._",
        "deployment._",
        "controllers._",
        "views.html.helper.magenta._",
        "com.gu.googleauth.AuthenticatedRequest"
      ),
      buildInfoKeys := Seq[BuildInfoKey](
        name, version, scalaVersion, sbtVersion,
        sbtbuildinfo.BuildInfoKey.constant("gitCommitId", System.getProperty("build.vcs.number", "DEV").dequote.trim),
        sbtbuildinfo.BuildInfoKey.constant("buildNumber", System.getProperty("build.number", "DEV").dequote.trim)
      ),
      buildInfoOptions += BuildInfoOption.BuildTime,
      buildInfoPackage := versionPackage
    )

  val magentaSettings: Seq[Setting[_]] = Seq(
    scalaVersion in ThisBuild := "2.11.8",
    scalacOptions ++= Seq("-deprecation", "-feature", "-language:postfixOps,reflectiveCalls,implicitConversions"),
    version := magentaVersion,
    resolvers += "Guardian Github Releases" at "http://guardian.github.com/maven/repo-releases",
    resolvers += "Brian Clapper Bintray" at "http://dl.bintray.com/bmc/maven"
  )

  val magentaVersion = "1.0"

  implicit def string2Dequote(s: String): Object {val dequote: String} = new {
    lazy val dequote = s.replace("\"", "")
  }
}
