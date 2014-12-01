import sbtassembly.Plugin._
import AssemblyKeys._
import java.util.jar.Attributes

libraryDependencies ++= Seq(
    "com.github.scopt" %% "scopt" % "3.2.0",
    "net.databinder" %% "dispatch-http" % "0.8.9"
)

seq(sbtassembly.Plugin.assemblySettings: _*)

packageOptions += {
    Package.ManifestAttributes(
      Attributes.Name.IMPLEMENTATION_VERSION -> System.getProperty("build.number", "DEV")
    )
}

jarName in assembly := "magenta.jar"
