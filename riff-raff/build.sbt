resolvers ++= Seq(
    "Guardian Github Snapshots" at "http://guardian.github.com/maven/repo-releases",
    "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/"
)


libraryDependencies ++= Seq(
  "com.gu" %% "management-play" % "6.0" exclude("javassist", "javassist"), // http://code.google.com/p/reflections/issues/detail?id=140
  "com.gu" %% "management-logback" % "5.31",
  "com.gu" %% "configuration" % "3.10",
  "org.scala-lang" % "scala-reflect" % "2.10.0",
  "org.mongodb" %% "casbah" % "2.6.0",
  "org.pircbotx" % "pircbotx" % "1.7",
  "com.typesafe.akka" %% "akka-agent" % "2.2.0",
  "org.clapper" %% "markwrap" % "1.0.0",
  "com.rabbitmq" % "amqp-client" % "2.8.7",
  "org.scalatest" %% "scalatest" % "1.9.1" % "test",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.1.3",
  "com.netflix.rxjava" % "rxjava-scala" % "0.17.1",
  filters
)

ivyXML :=
  <dependencies>
    <exclude org="commons-logging"><!-- Conflicts with jcl-over-slf4j in Play. --></exclude>
    <exclude org="org.springframework"><!-- Because I don't like it. --></exclude>
  </dependencies>

unmanagedClasspath in Test <+= (baseDirectory) map { bd => Attributed.blank(bd / "test") }

play.Project.lessEntryPoints <<= (sourceDirectory in Compile)(base => (
  (base / "assets" / "stylesheets" / "bootstrap" / "bootstrap.less") +++
  (base / "assets" / "stylesheets" / "bootstrap" / "responsive.less") +++
  (base / "assets" / "stylesheets" * "*.less" )
))
