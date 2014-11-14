import sbt._
import Keys._
import Defaults._
import sbtassembly.Plugin._
import AssemblyKeys._
import play.Keys._
import com.gu.deploy.MagentaArtifact._

object MagentaBuild extends Build {
  lazy val root = Project("root", file(".")) aggregate (lib, cli, riffraff)

  lazy val lib = magentaProject("magenta-lib")

  lazy val cli = magentaProject("magenta-cli") dependsOn(lib)

  lazy val riffraff = magentaPlayProject("riff-raff") dependsOn(lib)

  val liftVersion = "2.5-RC5"
  val guardianManagementVersion = "5.33"
  val guardianManagementPlayVersion = "6.2"

  def magentaProject(name: String) = Project(name, file(name), settings = defaultSettings ++ magentaSettings)

  def magentaPlayProject(name: String) = play.Project(name, magentaVersion, path=file(name))
    .settings( magentaArtifactSettings: _* )
    .settings( magentaSettings: _* )
    .settings(
      testOptions in Test := Nil,
      jarName in assembly := "%s.jar" format name,
      excludedJars in assembly <<= (fullClasspath in assembly) map { cp =>
        cp filter {jar => "scala-stm_2.10.0-0.6.jar" == jar.data.getName}
      },
      templatesImport ++= Seq(
        "magenta._",
        "deployment._",
        "controllers._",
        "views.html.helper.magenta._",
        "com.gu.googleauth.AuthenticatedRequest"
      ),
      mergeStrategy in assembly <<= (mergeStrategy in assembly) {
        (old) => {
          case "play/core/server/ServerWithStop.class" => MergeStrategy.first
          case x => old(x)
        }
      }
    )
    .settings(
      playExternalAssets <++= (baseDirectory) { base =>
        val mdPathFinder = (root:File) => root ** (FileFilter.globFilter("*.md") || FileFilter.globFilter("*.png"))
        val docsPrefix: String = "docs"
        Seq(
           (base / "app" / "docs", mdPathFinder, docsPrefix),
           (base / ".." / "magenta-lib" / "docs", mdPathFinder, docsPrefix)
        )
      }
    )

  val magentaSettings: Seq[Setting[_]] = Seq(
    scalaVersion := "2.10.3",
    scalacOptions ++= Seq("-deprecation", "-feature", "-language:postfixOps,reflectiveCalls,implicitConversions"),
    version := magentaVersion,
    resolvers += "Guardian Github Releases" at "http://guardian.github.com/maven/repo-releases"
  )

  val magentaVersion = "1.0"
}
