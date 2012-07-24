import sbt._

object DeployPlugins extends Build {
  val playArtifactPluginVersion = "1.7"

  lazy val plugins = Project("deploy-plugins", file("."))
    .settings(
      addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.1.0-M2-TYPESAFE")
    )
    .dependsOn(
    uri("git://github.com/guardian/sbt-teamcity-test-reporting-plugin.git#1.1"),
    uri("git://github.com/guardian/sbt-dist-plugin.git#1.5"),
    uri("git://github.com/guardian/sbt-play-artifact.git#" + playArtifactPluginVersion)
  )
}
