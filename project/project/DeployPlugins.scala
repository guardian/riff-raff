import sbt._

object DeployPlugins extends Build {
  val playArtifactPluginVersion = "1.6"

  lazy val plugins = Project("deploy-plugins", file("."))
    .settings(
      addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.0.0")
    )
    .dependsOn(
    uri("git://github.com/guardian/sbt-teamcity-test-reporting-plugin.git#1.1"),
    uri("git://github.com/guardian/sbt-dist-plugin.git#1.5"),
    uri("git://github.com/guardian/sbt-play-artifact.git#" + playArtifactPluginVersion)
  )
}
