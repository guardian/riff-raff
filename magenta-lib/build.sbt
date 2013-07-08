resolvers ++= Seq(
    Classpaths.typesafeResolver,
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/",
    "spray repo" at "http://repo.spray.cc",
    "Guardian Github Snapshots" at "http://guardian.github.com/maven/repo-releases"
)

libraryDependencies ++= Seq(
    "net.databinder" %% "dispatch-http" % "0.8.9",
    "net.liftweb" %% "lift-json" % liftVersion,
    "net.liftweb" %% "lift-util" % liftVersion,
    "org.bouncycastle" % "bcprov-jdk16" % "1.46",
    "org.bouncycastle" % "bcpg-jdk16" % "1.46",
    "com.decodified" % "scala-ssh_2.10.0-RC1" % "0.6.3",
    "ch.qos.logback" % "logback-classic" % "1.0.3",
    "com.amazonaws" % "aws-java-sdk" % "1.4.7",
    "org.scalatest" %% "scalatest" % "1.9.1" % "test",
    "org.mockito" % "mockito-core" % "1.9.0" % "test",
    "com.github.scala-incubator.io" %% "scala-io-core" % "0.4.2",
    "com.github.scala-incubator.io" %% "scala-io-file" % "0.4.2",
    "com.gu" %% "management" % "5.27"
)
