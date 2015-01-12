resolvers ++= Seq(
    Classpaths.typesafeResolver,
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/",
    "spray repo" at "http://repo.spray.cc"
)

libraryDependencies ++= Seq(
    "net.databinder" %% "dispatch-http" % "0.8.10",
    "org.json4s" %% "json4s-native" % "3.2.11",
    "org.bouncycastle" % "bcprov-jdk16" % "1.46",
    "org.bouncycastle" % "bcpg-jdk16" % "1.46",
    "com.decodified" %% "scala-ssh" % "0.7.0" exclude ("org.bouncycastle", "bcpkix-jdk15on"),
    "ch.qos.logback" % "logback-classic" % "1.1.2",
    "com.amazonaws" % "aws-java-sdk" % "1.9.8",
    "org.scalatest" %% "scalatest" % "2.2.2" % "test",
    "org.mockito" % "mockito-core" % "1.10.14" % "test",
    "com.github.scala-incubator.io" %% "scala-io-core" % "0.4.3",
    "com.github.scala-incubator.io" %% "scala-io-file" % "0.4.3",
    "com.gu" %% "management" % guardianManagementVersion,
    "com.gu" %% "fastly-api-client" % "0.2.4",
    "io.reactivex" %% "rxscala" % "0.23.0"
)

resourceDirectory in Compile := baseDirectory.value / "docs"
