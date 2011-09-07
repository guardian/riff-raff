package com.gu.deploy

import java.io.File
import json.{DeployInfoJsonReader, JsonReader}
import scopt.OptionParser
import tasks.CommandLocator

object Main extends App {

  object Config {
    var project: Option[String] = None
    var build: Option[String] = None
    var stage = ""
    var recipe = "default"
    var verbose = false
    var dryRun = false


    private var _di = "/opt/bin/deployinfo.json"

    def deployInfo_=(s: String) {
      val f = new File(s)
      if (!f.exists() || !f.isFile) sys.error("File not found.")
      _di = s
    }
    def deployInfo = _di

   lazy val parsedDeployInfo = {
      import sys.process._
      DeployInfoJsonReader.parse(_di.!!)
    }

    private var _localArtifactDir: Option[File] = None

    def localArtifactDir_=(dir: Option[File]) {
      dir.foreach { f =>
        if (!f.exists() || !f.isDirectory) sys.error("Directory not found.")
        Log.warn("Ignoring <project> and <build>; using local artifact directory of " + f.getAbsolutePath)
      }

      _localArtifactDir = dir
    }

    def localArtifactDir = _localArtifactDir
  }

  object CommandLineOutput extends Output with IndentingContext {
    def verbose(s: => String) { if (Config.verbose) Console.out.println(indent(s)) }
    def info(s: => String) { Console.out.println(indent(s)) }
    def warn(s: => String) { Console.out.println(indent("WARN: " + s)) }
    def error(s: => String) { Console.err.println(indent(s)) }
  }


  val programName = "deploy"
  val programVersion = "*PRE-ALPHA*"

  val parser = new OptionParser(programName, programVersion) {
    help("h", "help", "show this usage message")

    separator("\n  What to deploy:")
    opt("r", "recipe", "recipe to execute (default: 'default')", { r => Config.recipe = r })

    separator("\n  Diagnostic options:")
    opt("v", "verbose", "verbose logging", { Config.verbose = true } )
    opt("n", "dry-run", "don't execute any tasks, just show what would be done", { Config.dryRun = true })

    separator("\n  Advanced options:")
    opt("local-artifact", "Path to local artifact directory (overrides <project> and <build>)",
      { dir => Config.localArtifactDir = Some(new File(dir)) })
    opt("deployinfo", "use a different deployinfo script", { deployinfo => Config.deployInfo = deployinfo })
    opt("path", "Path for deploy support scripts (default: '/opt/deploy/bin')", { path => CommandLocator.rootPath = path })

    separator("\n")

    arg("<stage>", "Stage to deploy (e.g. TEST)", { s => Config.stage = s })
    argOpt("<project>", "TeamCity project name (e.g. tools::stats-aggregator)", { p => Config.project = Some(p) })
    argOpt("<build>", "TeamCity build number", { b => Config.build = Some(b) })

  }

  Log.current.withValue(CommandLineOutput) {
    if (parser.parse(args)) {
      try {
        Log.info("%s %s" format (programName, programVersion))

        Log.info("Locating artifact...")
        val dir = Config.localArtifactDir getOrElse Artifact.download(Config.project, Config.build)

        Log.info("Loading project file...")
        val project = JsonReader.parse(new File(dir, "deploy.json"))

        Log.verbose("Loaded: " + project)

        Log.info("Loading deployinfo...")
        val hostsInStage = Config.parsedDeployInfo.filter(_.stage == Config.stage)

        Log.verbose("All possible hosts in stage:\n" + dumpHostList(hostsInStage))

        if (hostsInStage.isEmpty)
          sys.error("No hosts found in stage %s; are you sure this is a valid stage?" format Config.stage)

        Log.info("Resolving...")
        val tasks = Resolver.resolve(project, Config.recipe, hostsInStage)

        if (tasks.isEmpty)
          sys.error("No tasks were found to execute. Most likely this is because no hosts with the required roles were found.")

        Log.context("Tasks to execute: ") {
          tasks.zipWithIndex.foreach { case (task, idx) =>
            Log.context("%d. %s" format (idx + 1, task.fullDescription)) {
              Log.verbose(task.verbose)
            }
          }
        }

        if (Config.dryRun) {
          Log.info("Dry run requested. Not executing.")
        } else {
          Log.info("Executing...")
          tasks.foreach { task =>
            Log.context("Executing %s..." format task.fullDescription) {
              task.execute()
            }
          }
          Log.info("Done")
        }

      } catch {
        case e: UsageError =>
          Log.error("Error: " + e.getMessage)
          parser.showUsage
        case e: Exception =>
          Log.error("FATAL: " + e.getMessage)
          if (Config.verbose) {
            e.printStackTrace()
          }
      }
    }
  }

  def dumpHostList(hosts: List[Host]) = {
    hosts
      .sortBy { _.name }
      .map { h => " %s: %s" format (h.name, h.roles.map { _.name } mkString ", ") }
      .mkString("\n")
  }
}