resolvers ++= Seq(
  Classpaths.typesafeReleases,
  "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/",
  Resolver.url("Typesafe Ivy Releases Repository", url("http://repo.typesafe.com/typesafe/ivy-releases/"))(Resolver.ivyStylePatterns)
)

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.3.7")

addSbtPlugin("com.typesafe.sbt" % "sbt-web" % "1.1.1")

addSbtPlugin("com.typesafe.sbt" % "sbt-less" % "1.0.4")

addSbtPlugin("com.typesafe.sbt" % "sbt-coffeescript" % "1.0.0")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.12.0")

addSbtPlugin("com.gu" % "sbt-teamcity-test-reporting-plugin" % "1.5")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.4")

addSbtPlugin("com.gu" % "riffraff-artifact" % "0.4")