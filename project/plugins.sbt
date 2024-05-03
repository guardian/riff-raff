// keep in sync with the play version in Dependencies
addSbtPlugin("org.playframework" % "sbt-plugin" % "3.0.2")

addSbtPlugin("com.github.sbt" % "sbt-coffeescript" % "2.0.1")
addSbtPlugin(
  "com.github.sbt" % "sbt-less" % "1.5.0"
) // scala-steward:off --  upgrading to 1.5 is causing a hard to debug error about mkdrip not existing.
addSbtPlugin("com.github.sbt" % "sbt-digest" % "2.0.0")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.12.0")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.12.1")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
