import Dependencies.*
import Helpers.*
import play.sbt.routes.RoutesKeys
import sbt.Def.spaceDelimited
import sbtbuildinfo.BuildInfoKeys.buildInfoKeys

import scala.sys.process.*

inThisBuild(
  List(
    semanticdbEnabled := true,
    semanticdbOptions += "-P:semanticdb:synthetics:on",
    semanticdbVersion := scalafixSemanticdb.revision,
    scalaBinaryVersion := "2.13",
    scalafixDependencies += "org.scala-lang" %% "scala-rewrites" % "0.1.5"
  )
)

val commonSettings = Seq(
  organization := "com.gu",
  scalaVersion := "2.13.17",
  scalacOptions ++= Seq(
    "-feature",
    "-language:postfixOps,reflectiveCalls,implicitConversions",
    "-Wconf:cat=other-match-analysis:error"
//    , "-Xfatal-warnings" TODO: Akka Agents have been deprecated. Once they have been replaced we can re-enable, but that's not trivial
  ),
  Compile / doc / scalacOptions ++= Seq(
    "-no-link-warnings" // Suppresses problems with Scaladoc @throws links
  ),
  version := "1.0"
)

lazy val root = project
  .in(file("."))
  .aggregate(lib, riffraff)
  .settings(
    name := "riff-raff"
  )

lazy val lib = project
  .in(file("magenta-lib"))
  .settings(commonSettings)
  .settings(
    Seq(
      libraryDependencies ++= magentaLibDeps,
      Test / testOptions += Tests.Argument("-oF")
    )
  )
  .settings {

    lazy val generateTypes =
      inputKey[Unit]("Download CloudFormation types into the source folder")

    generateTypes := {
      val profileName = spaceDelimited("<arg>").parsed match {
        case profileName :: Nil => profileName
        case other =>
          throw new IllegalArgumentException(
            s"Syntax: generateTypes <awsProfileName>\n(got: $other)"
          )
      }
      val className = "RegionCfnTypes"
      val regions = List("eu-west-1")
      val content: String = GenerateCfnTypes.generate(
        "magenta.tasks.stackSetPolicy.generated",
        className,
        regions,
        profileName
      )
      val targetFile =
        (Compile / scalaSource).value / "magenta" / "tasks" / "stackSetPolicy" / "generated" / (className + ".scala")
      println(
        s"writing...${content.length} characters to ${targetFile.toString}"
      )
      IO.write(targetFile, content)
      println("done")
    }

  }

def env(propName: String): String =
  sys.env.get(propName).filter(_.trim.nonEmpty).getOrElse("DEV")

lazy val riffraff = project
  .in(file("riff-raff"))
  .dependsOn(lib % "compile->compile;test->test")
  .enablePlugins(PlayScala, SbtWeb, BuildInfoPlugin)
  .settings(commonSettings)
  .settings(
    Seq(
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
        name,
        version,
        scalaVersion,
        sbtVersion,

        // These env vars are set by GitHub Actions
        // See https://docs.github.com/en/actions/learn-github-actions/variables#default-environment-variables
        "gitCommitId" -> env("GITHUB_SHA"),
        "buildNumber" -> env("BUILD_NUMBER")
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
      ivyXML := {
        <dependencies>
        <exclude org="commons-logging"><!-- Conflicts with acl-over-slf4j in Play. --> </exclude>
        <exclude org="oauth.signpost"><!-- Conflicts with play-googleauth--></exclude>
        <exclude org="org.springframework"><!-- Because I don't like it. --></exclude>
        <exclude org="xpp3"></exclude>
      </dependencies>
      },
      Test / fork := false,
      Test / testOptions += Tests.Setup(_ => {
        println(s"Starting Docker containers for tests")
        "docker compose up -d".!
      }),
      Test / testOptions += Tests.Cleanup(_ => {
        println(s"Stopping Docker containers for tests")
        "docker compose rm --stop --force".!
      }),
      Assets / LessKeys.less / includeFilter := "*.less",
      Assets / pipelineStages := Seq(digest)
    )
  )
