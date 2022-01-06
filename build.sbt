import Dependencies._
import Helpers._
import play.sbt.routes.RoutesKeys
import sbtbuildinfo.BuildInfoKeys.buildInfoKeys

val commonSettings = Seq(
  organization := "com.gu",
  scalaVersion := "2.13.7",

  scalacOptions ++= Seq("-feature", "-language:postfixOps,reflectiveCalls,implicitConversions"
//    , "-Xfatal-warnings" TODO: Akka Agents have been deprecated. Once they have been replaced we can re-enable, but that's not trivial
  ),
  Compile / doc / scalacOptions ++= Seq(
    "-no-link-warnings" // Suppresses problems with Scaladoc @throws links
  ),
  version := "1.0"
)

lazy val root = project.in(file("."))
  .aggregate(lib, riffraff)
  .settings(
    name := "riff-raff"
  )

lazy val lib = project.in(file("magenta-lib"))
  .settings(commonSettings)
  .settings(Seq(
    libraryDependencies ++= magentaLibDeps,

    Test / testOptions += Tests.Argument("-oF")
  ))

lazy val riffraff = project.in(file("riff-raff"))
  .dependsOn(lib % "compile->compile;test->test")
  .enablePlugins(PlayScala, SbtWeb, BuildInfoPlugin, RiffRaffArtifact)
  .settings(commonSettings)
  .settings(Seq(
    name := "riff-raff",
    TwirlKeys.templateImports ++= Seq(
      "magenta._",
      "deployment._",
      "controllers._",
      "views.html.helper.magenta._",
      "com.gu.googleauth.AuthenticatedRequest"
    ),

    RoutesKeys.routesImport += "utils.PathBindables._",

    buildInfoKeys := Seq[BuildInfoKey](
      name, version, scalaVersion, sbtVersion,
      "gitCommitId" -> riffRaffBuildInfo.value.revision,
      "buildNumber" -> riffRaffBuildInfo.value.buildIdentifier
    ),
    buildInfoOptions += BuildInfoOption.BuildTime,
    buildInfoPackage := "riffraff",

    libraryDependencies ++= riffRaffDeps,

    Universal / javaOptions ++= Seq(
      s"-Dpidfile.path=/dev/null",
      "-J-XX:MaxRAMFraction=2",
      "-J-XX:InitialRAMFraction=2",
      "-J-XX:MaxMetaspaceSize=300m",
      "-J-Xlog:gc*",
      s"-J-Xlog:gc:/var/log/${packageName.value}/gc.log"
    ),

    Universal / packageName := normalizedName.value,
    Universal / topLevelDirectory := Some(normalizedName.value),
    riffRaffPackageType := (Universal / packageZipTarball).value,
    riffRaffArtifactResources  := Seq(
      riffRaffPackageType.value -> s"${name.value}/${name.value}.tgz",
      baseDirectory.value / "bootstrap.sh" -> s"${name.value}/bootstrap.sh",
      baseDirectory.value / "riff-raff.yaml" -> "riff-raff.yaml"
    ),

    ivyXML := {
      <dependencies>
        <exclude org="commons-logging"><!-- Conflicts with acl-over-slf4j in Play. --> </exclude>
        <exclude org="oauth.signpost"><!-- Conflicts with play-googleauth--></exclude>
        <exclude org="org.springframework"><!-- Because I don't like it. --></exclude>
        <exclude org="xpp3"></exclude>
      </dependencies>
    },

    Test / fork := false,

    Assets / LessKeys.less / includeFilter := "*.less",

    Assets / pipelineStages := Seq(digest)
  ))
