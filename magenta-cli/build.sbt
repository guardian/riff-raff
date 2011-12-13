import sbtassembly.Plugin._
import AssemblyKeys._

libraryDependencies ++= Seq(
    "com.github.scopt" % "scopt_2.9.0-1" % "1.1.1",
    "net.databinder" %% "dispatch-http" % "0.8.5"
)

seq(sbtassembly.Plugin.assemblySettings: _*)

jarName in assembly := "magenta.jar"
