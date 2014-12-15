import play.PlayImport.PlayKeys._
import sbtassembly.Plugin.AssemblyKeys._

// TODO: Remove sonatype releases resolver
resolvers ++= Seq(
    "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases",
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/"
)

libraryDependencies ++= Seq(
  "com.gu" %% "management-play" % guardianManagementPlayVersion exclude("javassist", "javassist"), // http://code.google.com/p/reflections/issues/detail?id=140
  "com.gu" %% "management-logback" % guardianManagementVersion,
  "com.gu" %% "configuration" % "4.0",
  "com.gu" %% "play-googleauth" % "0.1.10",
  "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  "org.mongodb" %% "casbah" % "2.7.4",
  "org.pircbotx" % "pircbotx" % "1.7",
  "com.typesafe.akka" %% "akka-agent" % "2.3.7",
  "org.clapper" %% "markwrap" % "1.0.2",
  "com.rabbitmq" % "amqp-client" % "2.8.7",
  "org.scalatest" %% "scalatest" % "2.2.2" % "test",
  "io.reactivex" %% "rxscala" % "0.23.0",
  "org.parboiled" %% "parboiled" % "2.0.1",
  "com.adrianhurt" %% "play-bootstrap3" % "0.3",
  filters,
  ws,
  "org.scalatestplus" %% "play" % "1.2.0" % "test"
)

riffRaffPackageType := assembly.value

assemblySettings
mainClass in assembly := Some("play.core.server.NettyServer")
fullClasspath in assembly += Attributed.blank(playPackageAssets.value)

ivyXML :=
  <dependencies>
    <exclude org="commons-logging"><!-- Conflicts with acl-over-slf4j in Play. --> </exclude>
    <exclude org="oauth.signpost"><!-- Conflicts with play-googleauth--></exclude>
    <exclude org="org.springframework"><!-- Because I don't like it. --></exclude>
    <exclude org="xpp3"></exclude>
  </dependencies>

mergeStrategy in assembly <<= (mergeStrategy in assembly) {
  (old) => {
    case "play/core/server/ServerWithStop.class" => MergeStrategy.first
    case x => old(x)
  }
}

unmanagedClasspath in Test <+= (baseDirectory) map { bd => Attributed.blank(bd / "test") }

includeFilter in (Assets, LessKeys.less) := "*.less"

fork in Test := false

lazy val magenta = taskKey[File]("Alias to riffRaffArtifact for TeamCity compatibility")

magenta := riffRaffArtifact.value
