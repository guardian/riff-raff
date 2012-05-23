resolvers += "Guardian Github Snapshots" at "http://guardian.github.com/maven/repo-releases"

resolvers += Resolver.url("Typesafe Ivy Releases", url("http://repo.typesafe.com/typesafe/ivy-releases"))(Resolver.ivyStylePatterns)

libraryDependencies ++= Seq(
  "com.gu" %% "management-play" % "5.10",
  "com.gu" %% "management-logback" % "5.10"
)

ivyXML :=
  <dependencies>
    <exclude org="commons-logging"><!-- Conflicts with jcl-over-slf4j in Play. --></exclude>
    <exclude org="org.springframework"><!-- Because I don't like it. --></exclude>
  </dependencies>