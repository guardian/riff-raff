// keep in sync with the play version in Dependencies
addSbtPlugin("org.playframework" % "sbt-plugin" % "3.0.2")

addSbtPlugin("com.github.sbt" % "sbt-coffeescript" % "1.11.0")
addSbtPlugin("com.github.sbt" % "sbt-less" % "1.5.0")
addSbtPlugin("com.github.sbt" % "sbt-digest" % "2.0.0")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.12.0")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
