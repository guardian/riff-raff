resolvers += "spray repo" at "http://repo.spray.cc"

libraryDependencies ++= Seq(
	"net.databinder" %% "dispatch-http" % "0.8.5",
	"net.liftweb" %% "lift-json" % liftVersion,
	"net.liftweb" %% "lift-util" % liftVersion,
	"org.bouncycastle" % "bcprov-jdk16" % "1.46",
	"org.bouncycastle" % "bcpg-jdk16" % "1.46",
	"com.decodified" %% "scala-ssh" % "0.5.0",
	"com.amazonaws" % "aws-java-sdk" % "1.2.1",
	"org.scalatest" %% "scalatest" % "1.6.1" % "test",
	"org.mockito" % "mockito-core" % "1.9.0" % "test"
)
