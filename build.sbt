import Dependencies._
import Helpers._

val commonSettings = Seq(
  organization := "com.gu",
  scalaVersion := "2.12.8",
  scalacOptions ++= Seq("-deprecation", "-feature", "-language:postfixOps,reflectiveCalls,implicitConversions", "-Ypartial-unification"
//    , "-Xfatal-warnings" TODO: Akka Agents have been deprecated. Once they have been replaced we can re-enable, but that's not trivial
  ),
  scalacOptions in(Compile, doc) ++= Seq(
    "-no-link-warnings" // Suppresses problems with Scaladoc @throws links
  ),
  version := "1.0",
  resolvers ++= Seq(
    "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases/",
    "Guardian Github Releases" at "https://guardian.github.com/maven/repo-releases"
  )
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

    testOptions in Test += Tests.Argument("-oF")
  ))

lazy val riffraff = project.in(file("riff-raff"))
  .dependsOn(lib % "compile->compile;test->test")
  .enablePlugins(PlayScala, BuildInfoPlugin, RiffRaffArtifact)
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

    buildInfoKeys := Seq[BuildInfoKey](
      name, version, scalaVersion, sbtVersion,
      sbtbuildinfo.BuildInfoKey.constant("gitCommitId", System.getProperty("build.vcs.number", "DEV").dequote.trim),
      sbtbuildinfo.BuildInfoKey.constant("buildNumber", System.getProperty("build.number", "DEV").dequote.trim)
    ),
    buildInfoOptions += BuildInfoOption.BuildTime,
    buildInfoPackage := "riffraff",

    resolvers += "Brian Clapper Bintray" at "http://dl.bintray.com/bmc/maven",
    libraryDependencies ++= riffRaffDeps ++ migrationDeps,

    javaOptions in Universal ++= Seq(
      s"-Dpidfile.path=/dev/null",
      "-J-XX:MaxRAMFraction=2",
      "-J-XX:InitialRAMFraction=2",
      "-J-XX:MaxMetaspaceSize=300m",
      "-J-XX:+PrintGCDetails",
      "-J-XX:+PrintGCDateStamps",
      s"-J-Xloggc:/var/log/${packageName.value}/gc.log"
    ),
    
    packageName in Universal := normalizedName.value,
    topLevelDirectory in Universal := Some(normalizedName.value),
    riffRaffPackageType := (packageZipTarball in Universal).value,
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

    fork in Test := false,
    
    includeFilter in (Assets, LessKeys.less) := "*.less"
  ))
