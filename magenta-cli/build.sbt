import sbtassembly.Plugin._
import AssemblyKeys._
import java.util.jar.Attributes

libraryDependencies ++= Seq(
    "com.github.scopt" %% "scopt" % "2.1.0",
    "net.databinder" %% "dispatch-http" % "0.8.9"
)

seq(sbtassembly.Plugin.assemblySettings: _*)

packageOptions += {
    Package.ManifestAttributes(
      Attributes.Name.IMPLEMENTATION_VERSION -> System.getProperty("build.number", "DEV")
    )
}

mergeStrategy in assembly <<= (mergeStrategy in assembly) { current =>
  {
    case "NOTICE" => MergeStrategy.first
    case "version.txt" => MergeStrategy.first
    case meta if meta.startsWith("META-INF/") => MergeStrategy.first
    case other => current(other)
  }
}

excludedFiles in assembly := { (bases: Seq[File]) =>
  bases flatMap { base =>
    (base / "META-INF" * "*").get collect {
      case f if f.getName.toLowerCase == "license" => f
      case f if f.getName.toLowerCase == "manifest.mf" => f
      case f if f.getName.endsWith(".SF") => f
      case f if f.getName.endsWith(".DSA") => f
      case f if f.getName.endsWith(".RSA") => f
    }
  }}

jarName in assembly := "magenta.jar"
