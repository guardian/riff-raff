resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= Seq(
  "se.scalablesolutions.akka" % "akka-actor" % "1.2-RC6",
  "net.liftweb" %% "lift-webkit" % liftVersion,
  "net.liftweb" %% "lift-openid" % liftVersion,
  "ch.qos.logback" % "logback-classic" % "0.9.26",
  "org.mortbay.jetty" % "jetty" % "6.1.22" % "container"
)

seq(webSettings :_*)
