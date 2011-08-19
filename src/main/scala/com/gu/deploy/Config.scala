package com.gu.deploy

import org.fud.optparse.{OptionParserException, OptionParser}
import io.Source

case class Config(
 branch: String,
 build: Long,
 hosts: List[Host],
 debug: Boolean,
 overridehosts: List[Host],
 artifactdir: String,
 databaseChangeNumber: Option[Long],
 stage: String,
 appName: String) {
}


object Config {
  def fromCmdLine(args: Seq[String]) = {
    var build: Option[Int] = None
    var release: Option[String] = None
    var stage: Option[String] = None
    var debug: Boolean = false
    var changeNumber: Option[Long] = None
    var hosts: List[String] = Nil
    var artifactdir: Option[String] = None
    var deploytype: Option[String] = None

    val parser = new OptionParser {

      addArgumentParser[List[String]] { s =>
        s.split(',').toList.map(_.trim).filterNot(_.isEmpty)
      }

      banner = "remoteDeploy.sh [options]"
      separator("")
      separator("Required:")
      reqd("-b", "--build BUILDNUM", "Build number")
        { v: Int => build = Some(v) }
      reqd("-r", "--release RELEASE", "Release branch", "eg trunk, release-101")
        { v: String => release = Some(v) }
      reqd("", "--type DEPLOYTYPE", "Application name to deploy", "eg microapp-cache, content-api")
        { v: String => deploytype = Some(v) }

      separator("")
      separator("Optional:")
      reqd("", "--stage STAGE", "Environment to deploy to", "eg CODE, QA, TEST")
        { v: String => stage = Some(v) }
      flag("", "--debug", "Enable debugging to stdout")
        { () => debug = true }
      reqd("-d", "--change-num BUILDNUM", "Deploy only the specified change number")
        { v: Long=> changeNumber = Some(v) }
      reqd("-t", "--hosts HOST1[,HOST2[,...]]", "Deploy application to the specified hosts")
        { v: List[String] => hosts = v }
      flag("-h", "--help", "Show this message")
        { () => println(this); sys.exit(0) }

      separator("")
      separator("Internal option provided automatically by remoteDeploy.sh:")
      reqd("", "--artifactdir DIR", "Directory in which artifact can be found")
        { v: String => artifactdir = Some(v) }

      separator("")

    }

    try {
      parser.parse(args)

      Config(
        branch = release getOrElse sys.error("release is a required argument"),
        build = build getOrElse sys.error("build is a required argument"),
        hosts = Nil,
        debug = debug,
        overridehosts = hosts map Host,
        databaseChangeNumber = changeNumber,
        stage = stage getOrElse defaultStage,
        appName = deploytype getOrElse sys.error("type is a required argument"),
        artifactdir = artifactdir getOrElse sys.error("artifactdir is a required argument")
      )
    } catch {
      case e: OptionParserException => System.err.println("error: " + e.getMessage); sys.exit(1)
      case e: RuntimeException => System.err.println("error: " + e.getMessage); sys.exit(2)
      case other => System.err.println(other.getClass.getSimpleName + ": " + other.getMessage); sys.exit(3)
    }


  }

  lazy val defaultStage = {
    val StageRegex = "^STAGE=(.*)$".r

    val matchingStageLines = for {
      StageRegex(stage) <- Source.fromFile("/etc/gu/install_varsx").getLines()
    } yield stage

    matchingStageLines.toList.headOption getOrElse
      sys.error("you didn't specify a stage and there's no STAGE line in /etc/gu/install_vars")
  }

}