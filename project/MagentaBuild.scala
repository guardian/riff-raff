import java.net.InetAddress
import java.util.Date

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

  val buildNumber = System.getProperty("build.number", "DEV")
  val branch = System.getProperty("build.vcs.branch", "DEV")
  val vcsNumber = System.getProperty("build.vcs.number", "DEV")

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
      ),
      resourceGenerators in Compile += Def.task {
        buildFile((resourceManaged in Compile).value, branch, buildNumber, vcsNumber, streams.value)
      }.taskValue
    )

  val magentaSettings: Seq[Setting[_]] = Seq(
    scalacOptions ++= Seq("-deprecation", "-feature", "-language:postfixOps,reflectiveCalls,implicitConversions"),
    version := magentaVersion,
    resolvers += "Guardian Github Releases" at "http://guardian.github.com/maven/repo-releases",
    resolvers += "Brian Clapper Bintray" at "http://dl.bintray.com/bmc/maven"
  ) ++ net.virtualvoid.sbt.graph.Plugin.graphSettings

  val magentaVersion = "1.0"


  implicit def string2Dequote(s: String): Object {val dequote: String} = new {
    lazy val dequote = s.replace("\"", "")
  }

  def buildFile(outDir: File, branch: String, buildNum: String, vcsNum: String, s: TaskStreams) = {
    val versionInfo = Map(
      "Revision" -> vcsNum.dequote.trim,
      "Branch" -> branch.dequote.trim,
      "Build" -> buildNum.dequote.trim,
      "Date" -> new Date().toString,
      "Built-By" -> System.getProperty("user.name", "<unknown>"),
      "Built-On" -> InetAddress.getLocalHost.getHostName)

    val versionFileContents = (versionInfo map { case (x, y) => x + ": " + y }).toList.sorted

    val versionFile = outDir / "version.txt"
    s.log.debug("Writing to " + versionFile + ":\n   " + versionFileContents.mkString("\n   "))

    IO.write(versionFile, versionFileContents mkString "\n")

    Seq(versionFile)
  }
}
