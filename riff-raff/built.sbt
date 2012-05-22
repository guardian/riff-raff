resolvers += "Guardian Github Snapshots" at "http://guardian.github.com/maven/repo-releases"

libraryDependencies ++= Seq(
  "com.gu" %% "management-play" % "5.10",
  "com.gu" %% "management-logback" % "5.10"
)