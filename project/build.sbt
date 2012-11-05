resolvers ++= Seq(
  Classpaths.typesafeResolver,
  Resolver.url("Typesafe Ivy Releases Repository", url("http://repo.typesafe.com/typesafe/ivy-releases/"))(Resolver.ivyStylePatterns)
)

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.8.0")

addSbtPlugin("play" % "sbt-plugin" % "2.0.4")
