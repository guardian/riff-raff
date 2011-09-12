resolvers ++= Seq(
  "Web plugin repo" at "http://siasia.github.com/maven2",
  Resolver.url("Typesafe repository", new java.net.URL("http://typesafe.artifactoryonline.com/typesafe/ivy-releases/"))(Resolver.defaultIvyPatterns)
)

libraryDependencies <++= sbtVersion { sv => Seq(
    "com.eed3si9n" %% "sbt-assembly" % ("sbt" + sv + "_0.6"),
    "com.github.siasia" %% "xsbt-web-plugin" % ("0.1.0-" + sv)
)}
