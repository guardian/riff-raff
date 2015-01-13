import java.util.jar.Attributes

libraryDependencies ++= Seq(
    "com.github.scopt" %% "scopt" % "3.3.0",
    "net.databinder" %% "dispatch-http" % "0.8.10"
)

packageOptions += {
    Package.ManifestAttributes(
      Attributes.Name.IMPLEMENTATION_VERSION -> System.getProperty("build.number", "DEV")
    )
}

assemblyJarName in assembly := s"magenta.jar"
