import sbt._

object DeployPlugins extends Build {
  lazy val plugins = Project("deploy-plugins", file("."))
    .dependsOn(
    uri("git://github.com/guardian/sbt-teamcity-test-reporting-plugin.git#1.1"),
    uri("git://github.com/guardian/sbt-dist-plugin.git#1.5")
  )
}
