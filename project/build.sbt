resolvers ++= Seq(
  Classpaths.typesafeResolver,
  "sbt-idea-repo" at "http://mpeltonen.github.com/maven/",
  Resolver.url("Play 2.1-SNAPSHOT", url("http://guardian.github.com/ivy/repo-snapshots"))(Resolver.ivyStylePatterns)
)

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.8.0")

addSbtPlugin("play" % "sbt-plugin" % "2.1-SNAPSHOT")

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.0.0")