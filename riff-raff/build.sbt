resolvers += "Guardian Github Snapshots" at "http://guardian.github.com/maven/repo-releases"

resolvers += Resolver.url("Typesafe Ivy Releases", url("http://repo.typesafe.com/typesafe/ivy-releases"))(Resolver.ivyStylePatterns)

libraryDependencies ++= Seq(
  "com.gu" %% "management-play" % "5.10",
  "com.gu" %% "management-logback" % "5.10"
)
