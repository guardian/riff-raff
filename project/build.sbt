resolvers ++= Seq(
  Classpaths.typesafeResolver,
  Resolver.url("Typesafe Ivy Releases Repository", url("http://repo.typesafe.com/typesafe/ivy-releases/"))(Resolver.ivyStylePatterns)
)

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.8.7")

addSbtPlugin("com.gu" % "sbt-teamcity-test-reporting-plugin" % "1.5")
