resolvers ++= Seq(
    Classpaths.typesafeResolver,
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/",
    "spray repo" at "http://repo.spray.cc"
)

val awsVersion = "1.10.35"

libraryDependencies ++= Seq(
    "net.databinder" %% "dispatch-http" % "0.8.10",
    "org.json4s" %% "json4s-native" % "3.2.11",
    "org.bouncycastle" % "bcprov-jdk16" % "1.46",
    "org.bouncycastle" % "bcpg-jdk16" % "1.46",
    "com.decodified" %% "scala-ssh" % "0.7.0" exclude ("org.bouncycastle", "bcpkix-jdk15on"),
    "ch.qos.logback" % "logback-classic" % "1.1.2",
    "com.amazonaws" % "aws-java-sdk-core" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-autoscaling" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-s3" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-ec2" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-elasticloadbalancing" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-lambda" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-cloudformation" % awsVersion,
    "org.scalatest" %% "scalatest" % "2.2.3" % "test",
    "org.mockito" % "mockito-core" % "1.10.19" % "test",
    "com.github.scala-incubator.io" %% "scala-io-core" % "0.4.3",
    "com.github.scala-incubator.io" %% "scala-io-file" % "0.4.3",
    "org.parboiled" %% "parboiled" % "2.0.1",
    "com.gu" %% "management" % guardianManagementVersion,
    "com.gu" %% "fastly-api-client" % "0.2.5",
    "io.reactivex" %% "rxscala" % "0.23.0",
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % "2.7.1",
    "com.fasterxml.jackson.core" % "jackson-databind" % "2.7.1"
)

resourceDirectory in Compile := baseDirectory.value / "docs"
