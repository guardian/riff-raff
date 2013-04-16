resolvers ++= Seq(
    Classpaths.typesafeResolver,
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/",
    "spray repo" at "http://repo.spray.cc",
    "Guardian Github Snapshots" at "http://guardian.github.com/maven/repo-releases",
    "moschops releases" at "http://moschops.github.com/mvn/releases"
)

libraryDependencies ++= Seq(
    "net.databinder" %% "dispatch-http" % "0.8.5",
    "net.liftweb" %% "lift-json" % liftVersion,
    "net.liftweb" %% "lift-util" % liftVersion,
    "org.bouncycastle" % "bcprov-jdk16" % "1.46",
    "org.bouncycastle" % "bcpg-jdk16" % "1.46",
    "com.decodified" %% "scala-ssh" % "0.5.0",
    "ch.qos.logback" % "logback-classic" % "1.0.3",
    "com.amazonaws" % "aws-java-sdk" % "1.3.14",
    "org.scalatest" %% "scalatest" % "1.6.1" % "test",
    "org.mockito" % "mockito-core" % "1.9.0" % "test",
    "org.scala-sbt" %% "io" % "0.11.3",
    "com.gu" %% "management" % "5.19",
    "moschops" %% "fastlyapiclient" % "0.2.1" intransitive(),
    "commons-io" % "commons-io" % "2.1",
    "com.ning" % "async-http-client" % "1.7.6"
)
