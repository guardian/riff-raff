import play.sbt.PlayImport._
import sbt._

object Dependencies {

  object Versions {
    val aws = "1.11.106"
    val guardianManagement = "5.35"
    val guardianManagementPlay = "8.0"
    val jackson = "2.8.2"
    // keep in sync with plugin
    val play = "2.6.0"
  }

  val commonDeps = Seq(
    "io.reactivex" %% "rxscala" % "0.26.5",
    "org.parboiled" %% "parboiled" % "2.1.4",
    "org.typelevel" %% "cats" % "0.9.0",
    "com.github.cb372" %% "automagic" % "0.1",
    "org.scalatest" %% "scalatest" % "3.0.3" % Test,
    "org.mockito" % "mockito-core" % "1.10.19" % Test
  )

  val magentaLibDeps = commonDeps ++ Seq(
    "net.databinder" %% "dispatch-http" % "0.8.10",
    "org.bouncycastle" % "bcprov-jdk16" % "1.46",
    "org.bouncycastle" % "bcpg-jdk16" % "1.46",
    "com.github.seratch.com.veact" %% "scala-ssh" % "0.8.0-1" exclude ("org.bouncycastle", "bcpkix-jdk15on"),
    "ch.qos.logback" % "logback-classic" % "1.1.7",
    "com.amazonaws" % "aws-java-sdk-core" % Versions.aws,
    "com.amazonaws" % "aws-java-sdk-autoscaling" % Versions.aws,
    "com.amazonaws" % "aws-java-sdk-s3" % Versions.aws,
    "com.amazonaws" % "aws-java-sdk-ec2" % Versions.aws,
    "com.amazonaws" % "aws-java-sdk-elasticloadbalancing" % Versions.aws,
    "com.amazonaws" % "aws-java-sdk-elasticloadbalancingv2" % Versions.aws,
    "com.amazonaws" % "aws-java-sdk-lambda" % Versions.aws,
    "com.amazonaws" % "aws-java-sdk-cloudformation" % Versions.aws,
    "com.amazonaws" % "aws-java-sdk-sts" % Versions.aws,
    "com.gu" %% "management" % Versions.guardianManagement,
    "com.gu" %% "fastly-api-client" % "0.2.5",
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % Versions.jackson,
    "com.fasterxml.jackson.core" % "jackson-databind" % Versions.jackson,
    "com.typesafe.play" %% "play-json" % Versions.play
  ).map((m: ModuleID) =>
    // don't even ask why I need to do this
    m.excludeAll(ExclusionRule(organization = "com.google.code.findbugs", name = "jsr305"))
  )

  val riffRaffDeps = commonDeps ++ Seq(
    "com.gu" %% "management-internal" % "5.35",
    "com.gu" %% "management-logback" % Versions.guardianManagement,
    "com.gu" %% "configuration" % "4.0",
    "com.gu" %% "play-googleauth" % "0.7.0",
    "org.mongodb" %% "casbah" % "3.1.0",
    "com.typesafe.akka" %% "akka-agent" % "2.5.3",
    "org.pegdown" % "pegdown" % "1.6.0",
    "com.adrianhurt" %% "play-bootstrap" % "1.2-P26-B3-RC2",
    "com.gu" %% "scanamo" % "0.9.5",
    "com.amazonaws" % "aws-java-sdk-dynamodb" % Versions.aws,
    "org.webjars" %% "webjars-play" % "2.5.0",
    "org.webjars" % "jquery" % "3.1.1",
    "org.webjars" % "jquery-ui" % "1.12.1",
    "org.webjars" % "bootstrap" % "3.3.7",
    "org.webjars" % "jasny-bootstrap" % "3.1.3-2",
    "org.webjars" % "momentjs" % "2.16.0",
    filters,
    ws,
    "com.typesafe.akka" %% "akka-testkit" % "2.5.3" % Test,
    "org.gnieh" %% "diffson-play-json" % "2.2.1" % Test
  ).map((m: ModuleID) =>
    // don't even ask why I need to do this
    m.excludeAll(ExclusionRule(organization = "com.google.code.findbugs", name = "jsr305"))
  )

}
