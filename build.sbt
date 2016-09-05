import Dependencies._
import Helpers._

val commonSettings = Seq(
  organization := "com.gu",
  scalaVersion := "2.11.8",
  scalacOptions ++= Seq("-deprecation", "-feature", "-language:postfixOps,reflectiveCalls,implicitConversions"),
  version := "1.0",
  resolvers ++= Seq(
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/",
    "Guardian Github Releases" at "http://guardian.github.com/maven/repo-releases"
  )
)

lazy val root = project.in(file("."))
  .aggregate(lib, riffraff)

lazy val lib = project.in(file("magenta-lib"))
  .settings(commonSettings)
  .settings(Seq(
    libraryDependencies ++= magentaLibDeps,

    resourceDirectory in Compile := baseDirectory.value / "docs",

    testOptions in Test += Tests.Argument("-oF")
  ))

lazy val riffraff = project.in(file("riff-raff"))
  .dependsOn(lib)
  .enablePlugins(play.PlayScala, SbtWeb, RiffRaffArtifact, UniversalPlugin, BuildInfoPlugin)
  .settings(commonSettings)
  .settings(Seq(
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
    buildInfoPackage := "riffraff",

    resolvers += "Brian Clapper Bintray" at "http://dl.bintray.com/bmc/maven",
    libraryDependencies ++= riffRaffDeps,

    packageName in Universal := normalizedName.value,
    topLevelDirectory in Universal := Some(normalizedName.value),
    riffRaffPackageType := (packageZipTarball in Universal).value,
    riffRaffBuildIdentifier := sys.env.getOrElse("TRAVIS_BUILD_NUMBER", "DEV"), // TODO Travis??
    riffRaffUploadArtifactBucket := Some("riffraff-artifact"),
    riffRaffUploadManifestBucket := Some("riffraff-builds"),

    ivyXML := {
      <dependencies>
        <exclude org="commons-logging"><!-- Conflicts with acl-over-slf4j in Play. --> </exclude>
        <exclude org="oauth.signpost"><!-- Conflicts with play-googleauth--></exclude>
        <exclude org="org.springframework"><!-- Because I don't like it. --></exclude>
        <exclude org="xpp3"></exclude>
      </dependencies>
    },

    unmanagedClasspath in Test += Attributed.blank(baseDirectory.value / "test"), // TODO what's this for?
    fork in Test := false,

    includeFilter in (Assets, LessKeys.less) := "*.less",

    magenta := riffRaffArtifact.value
  ))

// TODO what's this about?
lazy val magenta = taskKey[File]("Alias to riffRaffArtifact for TeamCity compatibility")
