resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= Seq(
  "se.scalablesolutions.akka" % "akka-actor" % "1.2-RC6",
  "net.liftweb" %% "lift-webkit" % liftVersion,
  "ch.qos.logback" % "logback-classic" % "0.9.26",
  "org.eclipse.jetty" % "jetty-webapp" % "7.5.1.v20110908" % "jetty"
)

seq(webSettings :_*)
