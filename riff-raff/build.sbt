import play.PlayImport.PlayKeys._

// TODO: Remove sonatype releases resolver
resolvers ++= Seq(
    "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases",
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/"
)

libraryDependencies ++= Seq(
  "com.gu" %% "management-play" % guardianManagementPlayVersion exclude("javassist", "javassist"), // http://code.google.com/p/reflections/issues/detail?id=140
  "com.gu" %% "management-logback" % guardianManagementVersion,
  "com.gu" %% "configuration" % "4.0",
  "com.gu" %% "play-googleauth" % "0.2.2" exclude("com.google.guava", "guava-jdk5"),
  "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  "org.mongodb" %% "casbah" % "2.7.4",
  "org.pircbotx" % "pircbotx" % "1.7",
  "com.typesafe.akka" %% "akka-agent" % "2.3.8",
  "org.clapper" %% "markwrap" % "1.0.2",
  "com.rabbitmq" % "amqp-client" % "2.8.7",
  "org.scalatest" %% "scalatest" % "2.2.3" % "test",
  "io.reactivex" %% "rxscala" % "0.23.0",
  "org.parboiled" %% "parboiled" % "2.0.1",
  "com.adrianhurt" %% "play-bootstrap3" % "0.3",
  filters,
  ws,
  "org.scalatestplus" %% "play" % "1.2.0" % "test"
)

riffRaffPackageType := (packageZipTarball in Universal).value

packageName in Universal := normalizedName.value
topLevelDirectory in Universal := Some(normalizedName.value)

def env(key: String): Option[String] = Option(System.getenv(key))
riffRaffBuildIdentifier := env("TRAVIS_BUILD_NUMBER").getOrElse("DEV")
riffRaffUploadArtifactBucket := Option("riffraff-artifact")
riffRaffUploadManifestBucket := Option("riffraff-builds")

ivyXML :=
  <dependencies>
    <exclude org="commons-logging"><!-- Conflicts with acl-over-slf4j in Play. --> </exclude>
    <exclude org="oauth.signpost"><!-- Conflicts with play-googleauth--></exclude>
    <exclude org="org.springframework"><!-- Because I don't like it. --></exclude>
    <exclude org="xpp3"></exclude>
  </dependencies>

unmanagedClasspath in Test <+= (baseDirectory) map { bd => Attributed.blank(bd / "test") }

includeFilter in (Assets, LessKeys.less) := "*.less"

fork in Test := false

lazy val magenta = taskKey[File]("Alias to riffRaffArtifact for TeamCity compatibility")

magenta := riffRaffArtifact.value
