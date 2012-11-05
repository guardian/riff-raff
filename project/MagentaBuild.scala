import sbt._
import Keys._
import Defaults._
import sbtassembly.Plugin._
import AssemblyKeys._
import PlayKeys._
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
        cp filter {jar => List("io_2.9.1-0.11.2.jar","specs_2.9.0-1-1.6.8.jar").contains(jar.data.getName)}
      },
      templatesImport ++= Seq(
        "magenta._",
        "views.html.helper.magenta._"
      )
    )
    .settings(
      playExternalAssets <++= (baseDirectory) { base =>
        val mdPathFinder = (root:File) => root ** "*.md"
        val docsPrefix: String = "docs"
        Seq(
           (base / "app" / "docs", mdPathFinder, docsPrefix),
           (base / ".." / "magenta-lib" / "docs", mdPathFinder, docsPrefix)
        )
      }
    )

  val magentaSettings: Seq[Setting[_]] = Seq(
    scalaVersion := "2.9.1",
    scalacOptions ++= Seq("-deprecation"),
    version := magentaVersion
  )

  val magentaVersion = "1.0"
}