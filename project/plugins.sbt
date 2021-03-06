resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/"

// keep in sync with the play version in Dependencies
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.6.25")

addSbtPlugin("com.typesafe.sbt" % "sbt-coffeescript" % "1.0.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-less" % "1.1.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-digest" % "1.1.4")

addSbtPlugin("com.gu" % "sbt-teamcity-test-reporting-plugin" % "1.5")
addSbtPlugin("com.gu" % "sbt-riffraff-artifact" % "0.9.4")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.6.1")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.2")