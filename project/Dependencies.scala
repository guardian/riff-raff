import play.sbt.PlayImport._
import sbt._

object Dependencies {

  object Versions {
    val aws = "1.11.106"
    val guardianManagement = "5.41"
    val jackson = "2.8.2"
  }

  val commonDeps = Seq(
    "io.reactivex" %% "rxscala" % "0.26.5",
    "org.parboiled" %% "parboiled" % "2.1.5",
    "org.typelevel" %% "cats-core" % "1.0.1",
    "com.kailuowang" %% "henkan-convert" % "0.2.10",
    "org.scalatest" %% "scalatest" % "3.0.3" % Test,
    "org.mockito" % "mockito-core" % "1.10.19" % Test
  )

  val magentaLibDeps = commonDeps ++ Seq(
    "com.squareup.okhttp3" % "okhttp" % "3.12.1",
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
    "com.gu" %% "fastly-api-client" % "0.2.6",
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % Versions.jackson,
    "com.fasterxml.jackson.core" % "jackson-databind" % Versions.jackson,
    "com.typesafe.play" %% "play-json" % "2.6.13",
    "com.beachape" %% "enumeratum-play-json" % "1.5.14"
  ).map((m: ModuleID) =>
    // don't even ask why I need to do this
    m.excludeAll(ExclusionRule(organization = "com.google.code.findbugs", name = "jsr305"))
  )

  val riffRaffDeps = commonDeps ++ Seq(
    evolutions,
    jdbc,
    "com.gu" %% "management-internal" % Versions.guardianManagement,
    "com.gu" %% "management-logback" % Versions.guardianManagement,
    "com.gu" %% "play-googleauth" % "0.7.7",
    "com.gu.play-secret-rotation" %% "aws-parameterstore" % "0.12",
    "org.mongodb" %% "casbah" % "3.1.1",
    "com.typesafe.akka" %% "akka-agent" % "2.5.17",
    "org.pegdown" % "pegdown" % "1.6.0",
    "com.adrianhurt" %% "play-bootstrap" % "1.2-P26-B3-RC2",
    "com.gu" %% "scanamo" % "1.0.0-M6",
    "com.amazonaws" % "aws-java-sdk-dynamodb" % Versions.aws,
    "org.quartz-scheduler" % "quartz" % "2.3.0",
    "com.gu" %% "anghammarad-client" % "1.0.4",
    "org.webjars" %% "webjars-play" % "2.6.0",
    "org.webjars" % "jquery" % "3.1.1",
    "org.webjars" % "jquery-ui" % "1.12.1",
    "org.webjars" % "bootstrap" % "3.3.7",
    "org.webjars" % "jasny-bootstrap" % "3.1.3-2",
    "org.webjars" % "momentjs" % "2.16.0",
    "net.logstash.logback" % "logstash-logback-encoder" % "5.1",
    "com.gu" % "kinesis-logback-appender" % "1.4.2",
    "org.scalikejdbc" %% "scalikejdbc" % "3.3.0",
    "org.postgresql" % "postgresql" % "42.2.5",
    filters,
    ws,
    "com.typesafe.akka" %% "akka-testkit" % "2.5.17" % Test,
    "org.gnieh" %% "diffson-play-json" % "2.2.1" % Test
  ).map((m: ModuleID) =>
    // don't even ask why I need to do this
    m.excludeAll(ExclusionRule(organization = "com.google.code.findbugs", name = "jsr305"))
  )

}
