resolvers ++= Seq(
    "Guardian Github Snapshots" at "http://guardian.github.com/maven/repo-releases",
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/"
)


libraryDependencies ++= Seq(
  "com.gu" %% "management-play" % "5.19",
  "com.gu" %% "management-logback" % "5.19",
  "com.gu" %% "configuration" % "3.6",
  "com.novus" %% "salat" % "1.9.1",
  "org.pircbotx" % "pircbotx" % "1.7",
  "com.typesafe.akka" % "akka-agent" % "2.0.2",
  "org.clapper" %% "markwrap" % "0.5.4",
  "com.rabbitmq" % "amqp-client" % "2.8.7",
  "org.scalatest" %% "scalatest" % "1.6.1" % "test"
)

ivyXML :=
  <dependencies>
    <exclude org="commons-logging"><!-- Conflicts with jcl-over-slf4j in Play. --></exclude>
    <exclude org="org.springframework"><!-- Because I don't like it. --></exclude>
  </dependencies>

unmanagedClasspath in Test <+= (baseDirectory) map { bd => Attributed.blank(bd / "test") }