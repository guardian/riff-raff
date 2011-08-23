package com.gu.deploy

import java.io.File
import scopt.OptionParser
import com.gu.deploy2.json.JsonReader


object Main extends App {

  object Config {
    var project: File = _
    var recipe: Option[String] = None
    var verbose = false

    def project_= (s: String) {
      val f = new File(s)
      if (!f.exists() || !f.isFile) sys.error("File not found.")
      project = f
    }
  }


  val parser = new OptionParser("deploy", "*PRE-ALPHA*") {
    arg("<project>", "json deploy project file", { Config.project = _ })
    opt("r", "recipe", "recipe to execute", { r => Config.recipe = Some(r) })
    booleanOpt("v", "verbose", "verbose logging", { b => Config.verbose = b } )
  }

  if (parser.parse(args)) {
    try {
      println("project = " + Config.project + " recipe = " + Config.recipe)
      JsonReader.parse(Config.project)
    } catch {
      case e: Exception =>
        println("FATAL: " + e)
        if (Config.verbose) {
          e.printStackTrace()
        }
    }
  }
}