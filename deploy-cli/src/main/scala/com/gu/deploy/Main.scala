package com.gu.deploy

import java.io.File
import json.{DeployInfoJsonReader, JsonReader}
import scopt.OptionParser
object Main extends App {

  object Config {
    var project: File = _
    var recipe: String = "default"
    var stage: String = "PROD"
    var _di: String = "/opt/bin/deployinfo.json"
    var verbose = false
    var dryRun = false

    def project_= (s: String) {
      val f = new File(s)
      if (!f.exists() || !f.isFile) sys.error("File not found.")
      project = f
    }

    def deployInfo_= (s:String) {
      val f = new File(s)
      if (!f.exists() || !f.isFile) sys.error("File not found.")
      _di = s
    }
    def deployInfo = {
      import sys.process._
      DeployInfoJsonReader.parse(_di.!!)
    }
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
    arg("<project>", "json deploy project file", { p => Config.project = p })
    opt("r", "recipe", "recipe to execute (default: 'default')", { r => Config.recipe = r })
    opt("v", "verbose", "verbose logging", { Config.verbose = true } )
    opt("n", "dry-run", "don't execute any tasks, just show what would be done", { Config.dryRun = true })
    opt("s", "stage", "stage to deploy to (default: 'PROD')", { s => Config.stage = s })
    opt("deployinfo", "use a different deployinfo script", {deployinfo:String => Config.deployInfo = deployinfo})
  }

  Log.current.withValue(CommandLineOutput) {
    if (parser.parse(args)) {
      try {
        Log.info("%s %s" format (programName, programVersion))

        Log.info("Loading project file...")
        val project = JsonReader.parse(Config.project)

        Log.verbose("Loaded: " + project)

        Log.info("Loading deployinfo... (CURRENTLY STUBBED)")
        val hosts = Config.deployInfo.filter(_.stage == Config.stage)

        Log.info("Resolving...")
        val tasks = Resolver.resolve(project, Config.recipe, hosts)

        Log.context("Tasks to execute: ") {
          tasks.zipWithIndex.foreach { case (task, idx) =>
            Log.context("%d. %s" format (idx + 1, task.fullDescription)) {
              Log.verbose(task.verbose)
            }
          }
        }

        if (!Config.dryRun) {
          Log.info("Executing...")
          tasks.foreach { task =>
            Log.context("Executing %s..." format task.fullDescription) {
              task.execute()
            }
          }
        }

        Log.info("Done")

      } catch {
        case e: Exception =>
          Log.error("FATAL: " + e)
          if (Config.verbose) {
            e.printStackTrace()
          }
      }
    }
  }
}