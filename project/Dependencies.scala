import play.sbt.PlayImport._
import sbt._

object Dependencies {

  object Versions {
    val aws = "2.17.109"
    val jackson = "2.9.8"
    val awsRds = "1.11.563"
    val enumeratumPlay = "1.7.0"
  }

  val commonDeps = Seq(
    "io.reactivex" %% "rxscala" % "0.27.0",
    "org.parboiled" %% "parboiled" % "2.4.0",
    "org.typelevel" %% "cats-core" % "2.7.0",
    "org.scalatest" %% "scalatest" % "3.2.9" % Test,
    "org.mockito" %% "mockito-scala" % "1.16.37" % Test
  )

  val magentaLibDeps = commonDeps ++ Seq(
    "com.squareup.okhttp3" % "okhttp" % "3.14.0",
    "org.bouncycastle" % "bcprov-jdk15on" % "1.61",
    "org.bouncycastle" % "bcpg-jdk15on" % "1.61",
    "ch.qos.logback" % "logback-classic" % "1.2.0",
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
    "com.gu" %% "fastly-api-client" % "0.4.1",
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % Versions.jackson,
    "com.fasterxml.jackson.core" % "jackson-databind" % Versions.jackson,
    "com.typesafe.play" %% "play-json" % "2.8.2",
    "com.beachape" %% "enumeratum-play-json" % Versions.enumeratumPlay,
    "com.google.apis" % "google-api-services-deploymentmanager" % "v2-rev75-1.25.0",
    "com.google.cloud" % "google-cloud-storage" % "2.2.3",
    "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4"
  ).map((m: ModuleID) =>
    // don't even ask why I need to do this
    m.excludeAll(ExclusionRule(organization = "com.google.code.findbugs", name = "jsr305"))
  )

  val riffRaffDeps = commonDeps ++ Seq(
    evolutions,
    jdbc,
    "com.gu.play-googleauth" %% "play-v28" % "2.2.2",
    "com.gu.play-secret-rotation" %% "play-v28" % "0.33",
    "com.gu.play-secret-rotation" %% "aws-parameterstore-sdk-v2" % "0.33",
    "com.typesafe.akka" %% "akka-agent" % "2.5.26",
    "org.pegdown" % "pegdown" % "1.6.0",
    "com.adrianhurt" %% "play-bootstrap" % "1.6.1-P28-B3",
    "org.scanamo" %% "scanamo" % "1.0.0-M11",
    "software.amazon.awssdk" % "dynamodb" % Versions.aws,
    "software.amazon.awssdk" % "sns" % Versions.aws,
    "org.quartz-scheduler" % "quartz" % "2.3.2",
    "com.gu" %% "anghammarad-client" % "1.2.0",
    "org.webjars" %% "webjars-play" % "2.8.13",
    "org.webjars" % "jquery" % "3.1.1",
    "org.webjars" % "jquery-ui" % "1.13.0",
    "org.webjars" % "bootstrap" % "3.3.7",
    "org.webjars" % "jasny-bootstrap" % "3.1.3-2",
    "org.webjars" % "momentjs" % "2.16.0",
    "net.logstash.logback" % "logstash-logback-encoder" % "5.3",
    "com.gu" % "kinesis-logback-appender" % "1.4.4",
    "org.slf4j" % "jul-to-slf4j" % "1.7.30",
    "org.scalikejdbc" %% "scalikejdbc" % "3.5.0",
    "org.postgresql" % "postgresql" % "42.3.2",
    "com.beachape" %% "enumeratum-play" % Versions.enumeratumPlay,
    filters,
    ws,
    "com.typesafe.akka" %% "akka-testkit" % "2.6.17" % Test,
    "com.amazonaws" % "aws-java-sdk-rds" % Versions.awsRds
  ).map((m: ModuleID) =>
    // don't even ask why I need to do this
    m.excludeAll(ExclusionRule(organization = "com.google.code.findbugs", name = "jsr305"))
  )
}
