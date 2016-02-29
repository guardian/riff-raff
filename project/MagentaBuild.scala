import sbt._
import Keys._
import play.twirl.sbt.Import._
import com.typesafe.sbt.web.SbtWeb
import com.gu.riffraff.artifact.RiffRaffArtifact
import com.typesafe.sbt.packager.universal.UniversalPlugin

object MagentaBuild extends Build {
  lazy val root = Project("root", file(".")) aggregate (lib, cli, riffraff)

  lazy val lib = magentaProject("magenta-lib")

  lazy val cli = magentaProject("magenta-cli") dependsOn(lib)

  lazy val riffraff = magentaPlayProject("riff-raff") dependsOn(lib)

  val guardianManagementVersion = "5.35"
  val guardianManagementPlayVersion = "7.2"

  def magentaProject(name: String) = Project(name, file(name)).settings(magentaSettings: _*)

  def magentaPlayProject(name: String) = Project(name, file(name))
    .enablePlugins(play.PlayScala, SbtWeb, RiffRaffArtifact, UniversalPlugin)
    .settings( magentaSettings: _* )
    .settings(
      testOptions in Test := Nil,
      TwirlKeys.templateImports ++= Seq(
        "magenta._",
        "deployment._",
        "controllers._",
        "views.html.helper.magenta._",
        "com.gu.googleauth.AuthenticatedRequest"
      )
    )

  val magentaSettings: Seq[Setting[_]] = Seq(
    scalacOptions ++= Seq("-deprecation", "-feature", "-language:postfixOps,reflectiveCalls,implicitConversions"),
    version := magentaVersion,
    resolvers += "Guardian Github Releases" at "http://guardian.github.com/maven/repo-releases",
    resolvers += "Brian Clapper Bintray" at "http://dl.bintray.com/bmc/maven"
  ) ++ net.virtualvoid.sbt.graph.Plugin.graphSettings

  val magentaVersion = "1.0"
}
