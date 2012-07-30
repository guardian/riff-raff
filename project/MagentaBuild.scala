import sbt._
import Keys._
import Defaults._
import sbtassembly.Plugin._
import AssemblyKeys._
import PlayProject.{SCALA,templatesImport}
import com.gu.deploy.PlayArtifact._
import org.sbtidea.SbtIdeaPlugin._

object MagentaBuild extends Build {
  lazy val root = Project("root", file(".")) aggregate (lib, cli, riffraff) settings (ideaSettings: _*)

  lazy val lib = magentaProject("magenta-lib")

  lazy val cli = magentaProject("magenta-cli") dependsOn(lib)

  lazy val riffraff = magentaPlayProject("riff-raff") dependsOn(lib)

  val liftVersion = "2.4-M4"

  def magentaProject(name: String) = Project(name, file(name), settings = defaultSettings ++ magentaSettings ++ ideaSettings)

  def magentaPlayProject(name: String) = PlayProject(name, magentaVersion, path=file(name), mainLang=SCALA)
    .settings( playArtifactDistSettings: _* )
    .settings( magentaSettings: _* )
    .settings( ideaSettings: _* )
    .settings(
      testOptions in Test := Nil,
      jarName in assembly := "%s.jar" format name,
      excludedJars in assembly <<= (fullClasspath in assembly) map { cp =>
        cp filter {_.data.getName == "io_2.9.1-0.11.2.jar"}
      },
      templatesImport ++= Seq(
        "magenta._",
        "views.html.helper.magenta._"
      )
  )


  val magentaSettings: Seq[Setting[_]] = Seq(
    scalaVersion := "2.9.1",
    scalacOptions ++= Seq("-deprecation"),
    version := magentaVersion
  )

  val magentaVersion = "1.0"
}