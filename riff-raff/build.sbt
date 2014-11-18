resolvers ++= Seq(
    "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/"
)


libraryDependencies ++= Seq(
  "com.gu" %% "management-play" % guardianManagementPlayVersion exclude("javassist", "javassist"), // http://code.google.com/p/reflections/issues/detail?id=140
  "com.gu" %% "management-logback" % guardianManagementVersion,
  "com.gu" %% "configuration" % "4.0",
  "com.gu" %% "play-googleauth" % "0.1.7",
  "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  "org.mongodb" %% "casbah" % "2.7.4",
  "org.pircbotx" % "pircbotx" % "1.7",
  "com.typesafe.akka" %% "akka-agent" % "2.3.7",
  "org.clapper" %% "markwrap" % "1.0.2",
  "com.rabbitmq" % "amqp-client" % "2.8.7",
  "org.scalatest" %% "scalatest" % "2.2.2" % "test",
//  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.1.3",
  "com.netflix.rxjava" % "rxjava-scala" % "0.17.1",
  "org.parboiled" %% "parboiled" % "2.0.1",
  filters,
  ws
)

ivyXML :=
  <dependencies>
    <exclude org="commons-logging"><!-- Conflicts with jcl-over-slf4j in Play. --></exclude>
    <exclude org="org.springframework"><!-- Because I don't like it. --></exclude>
  </dependencies>

unmanagedClasspath in Test <+= (baseDirectory) map { bd => Attributed.blank(bd / "test") }

includeFilter in (Assets, LessKeys.less) := "*.less"
