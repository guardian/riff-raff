import play.sbt.PlayImport._
import sbt._

object Dependencies {

  object Versions {
    val aws = "2.20.147"
    val jackson = "2.15.2"
    val awsRds = "1.12.549"
    val enumeratumPlay = "1.7.3"
  }

  // https://github.com/orgs/playframework/discussions/11222
  private val jacksonOverrides = Seq(
    "com.fasterxml.jackson.core" % "jackson-core",
    "com.fasterxml.jackson.core" % "jackson-annotations",
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8",
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310",
    "com.fasterxml.jackson.core" % "jackson-databind"
  ).map(_ % Versions.jackson)

  private val akkaSerializationJacksonOverrides = Seq(
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor",
    "com.fasterxml.jackson.module" % "jackson-module-parameter-names",
    "com.fasterxml.jackson.module" %% "jackson-module-scala"
  ).map(_ % Versions.jackson)

  val commonDeps = Seq(
    "io.reactivex" %% "rxscala" % "0.27.0",
    "org.scalatest" %% "scalatest" % "3.2.17" % Test,
    "org.parboiled" %% "parboiled" % "2.5.0",
    "org.typelevel" %% "cats-core" % "2.10.0",
    "org.mockito" %% "mockito-scala" % "1.17.22" % Test
  )

  val magentaLibDeps =
    commonDeps ++ jacksonOverrides ++ akkaSerializationJacksonOverrides ++ Seq(
      "com.squareup.okhttp3" % "okhttp" % "4.11.0",
      "ch.qos.logback" % "logback-classic" % "1.4.8", // scala-steward:off
      "software.amazon.awssdk" % "core" % Versions.aws,
      "software.amazon.awssdk" % "autoscaling" % Versions.aws,
      "software.amazon.awssdk" % "s3" % Versions.aws,
      "software.amazon.awssdk" % "ec2" % Versions.aws,
      "software.amazon.awssdk" % "elasticloadbalancing" % Versions.aws,
      "software.amazon.awssdk" % "elasticloadbalancingv2" % Versions.aws,
      "software.amazon.awssdk" % "lambda" % Versions.aws,
      "software.amazon.awssdk" % "cloudformation" % Versions.aws,
      "software.amazon.awssdk" % "sts" % Versions.aws,
      "software.amazon.awssdk" % "ssm" % Versions.aws,
      "com.gu" %% "fastly-api-client" % "0.6.0",
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % Versions.jackson,
      "com.fasterxml.jackson.core" % "jackson-databind" % Versions.jackson,
      "com.typesafe.play" %% "play-json" % "2.9.4",
      "com.beachape" %% "enumeratum-play-json" % Versions.enumeratumPlay,
      "com.google.apis" % "google-api-services-deploymentmanager" % "v2-rev20230821-2.0.0",
      "com.google.cloud" % "google-cloud-storage" % "2.27.0",
      "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4"
    ).map((m: ModuleID) =>
      // don't even ask why I need to do this
      m.excludeAll(
        ExclusionRule(
          organization = "com.google.code.findbugs",
          name = "jsr305"
        )
      )
    )

  val riffRaffDeps =
    commonDeps ++ jacksonOverrides ++ akkaSerializationJacksonOverrides ++ Seq(
      evolutions,
      jdbc,
      "com.gu.play-googleauth" %% "play-v28" % "2.2.7",
      "com.gu.play-secret-rotation" %% "play-v28" % "0.38",
      "com.gu.play-secret-rotation" %% "aws-parameterstore-sdk-v2" % "0.38",
      "com.typesafe.akka" %% "akka-agent" % "2.5.32",
      "org.pegdown" % "pegdown" % "1.6.0",
      "com.adrianhurt" %% "play-bootstrap" % "1.6.1-P28-B3", // scala-steward:off,
      "org.scanamo" %% "scanamo" % "1.0.0-M11", // scala-steward:off,
      "software.amazon.awssdk" % "dynamodb" % Versions.aws,
      "software.amazon.awssdk" % "sns" % Versions.aws,
      "org.quartz-scheduler" % "quartz" % "2.3.2",
      "com.gu" %% "anghammarad-client" % "1.7.5",
      "org.webjars" %% "webjars-play" % "2.8.18",
      "org.webjars" % "jquery" % "3.7.1",
      "org.webjars" % "jquery-ui" % "1.13.2",
      "org.webjars" % "bootstrap" % "3.4.1", // scala-steward:off
      "org.webjars" % "jasny-bootstrap" % "3.1.3-2", // scala-steward:off
      "org.webjars" % "momentjs" % "2.29.4",
      "net.logstash.logback" % "logstash-logback-encoder" % "7.4",
      "com.gu" % "kinesis-logback-appender" % "2.1.1",
      // Similar to logback-classic, update when Play supports logback 1.3+ / SLF4J 2+
      "org.slf4j" % "jul-to-slf4j" % "1.7.36", // scala-steward:off
      // We can't update this to 4.0.0 due to an incompatibility with Play 2.8.x, attempt to update along with Play
      "org.scalikejdbc" %% "scalikejdbc" % "3.5.0", // scala-steward:off
      "org.postgresql" % "postgresql" % "42.6.0",
      "com.beachape" %% "enumeratum-play" % Versions.enumeratumPlay,
      filters,
      ws,
      // We can't update this to 4.0.0 due to an incompatibility with Play 2.8.x, attempt to update along with Play
      "com.typesafe.akka" %% "akka-testkit" % "2.6.21" % Test,
      "com.amazonaws" % "aws-java-sdk-rds" % Versions.awsRds
    ).map((m: ModuleID) =>
      // don't even ask why I need to do this
      m.excludeAll(
        ExclusionRule(
          organization = "com.google.code.findbugs",
          name = "jsr305"
        )
      )
    )
}
