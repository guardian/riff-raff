resolvers ++= Seq(
  Classpaths.typesafeResolver,
  Resolver.url("Typesafe Ivy Releases Repository", url("http://repo.typesafe.com/typesafe/ivy-releases/"))(Resolver.ivyStylePatterns),
  Resolver.url("Play 2.1-SNAPSHOT", url("http://guardian.github.com/ivy/repo-snapshots"))(Resolver.ivyStylePatterns)
)

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.8.0")

addSbtPlugin("play" % "sbt-plugin" % "2.1-06142012")
