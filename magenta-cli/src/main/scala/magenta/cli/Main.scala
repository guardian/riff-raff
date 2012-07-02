package magenta
package cli

import java.io.File
import json.{DeployInfoJsonReader, JsonReader}
import scopt.OptionParser
import HostList._
import tasks.CommandLocator
import magenta.NoHostsFoundException

object Main extends scala.App {

  object Config {

    var project: Option[String] = None
    var build: Option[String] = None
    var host: Option[String] = None
    var stage = ""
    var recipe = "default"
    var verbose = false
    var dryRun = false

    var keyLocation: Option[File] = None
    var jvmSsh = false

    var deployInfo = "/opt/bin/deployinfo.json"

    lazy val parsedDeployInfo = {
      import sys.process._
      DeployInfoJsonReader.parse(deployInfo.!!)
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

  object ManagementBuildInfo {
    lazy val version = Option(getClass.getPackage.getImplementationVersion) getOrElse "DEV"
  }

  val programName = "magenta"
  val programVersion = ManagementBuildInfo.version

  val parser = new OptionParser(programName, programVersion) {
    help("h", "help", "show this usage message")

    separator("\n  What to deploy:")
    opt("r", "recipe", "recipe to execute (default: 'default')", { r => Config.recipe = r })
    opt("t", "host", "only deply to the named host", { h => Config.host = Some(h) })

    separator("\n  Diagnostic options:")
    opt("v", "verbose", "verbose logging", { Config.verbose = true } )
    opt("n", "dry-run", "don't execute any tasks, just show what would be done", { Config.dryRun = true })

    separator("\n  Advanced options:")
    opt("local-artifact", "Path to local artifact directory (overrides <project> and <build>)",
      { dir => Config.localArtifactDir = Some(new File(dir)) })
    opt("deployinfo", "use a different deployinfo script", { deployinfo => Config.deployInfo = deployinfo })
    opt("path", "Path for deploy support scripts (default: '/opt/deploy/bin')", { path => CommandLocator.rootPath = path })
    opt("i", "keyLocation", "specify location of SSH key file", {keyLocation => Config.keyLocation = Some(validFile(keyLocation))})
    opt("j", "jvm-ssh", "perform ssh within the JVM, rather than shelling out to do so", { Config.jvmSsh = true })

    separator("\n")

    arg("<stage>", "Stage to deploy (e.g. TEST)", { s => Config.stage = s })
    argOpt("<project>", "TeamCity project name (e.g. tools::stats-aggregator)", { p => Config.project = Some(p) })
    argOpt("<build>", "TeamCity build number", { b => Config.build = Some(b) })

  }

  def validFile(s: String) = {
    val file = new File(s)
    if (file.exists() && file.isFile) file else sys.error("File not found: %s" format (s))
  }

  Log.current.withValue(CommandLineOutput) {
    if (parser.parse(args)) {
      try {
        Log.info("%s build %s" format (programName, programVersion))

        Log.info("Locating artifact...")
        val dir = Config.localArtifactDir getOrElse Artifact.download(Config.project, Config.build)

        Log.info("Loading project file...")
        val project = JsonReader.parse(new File(dir, "deploy.json"))

        Log.verbose("Loaded: " + project)

        Log.info("Loading deployinfo...")
        val hostsInStage = Config.parsedDeployInfo.filter(_.stage == Config.stage)

        Log.verbose("All possible hosts in stage:\n" + hostsInStage.dump)

        val hosts = Config.host map { h => hostsInStage.filter(_.name == h) } getOrElse hostsInStage

        Log.info("Resolving...")
        val tasks = try Resolver.resolve(project, Config.recipe, hosts, Stage(Config.stage)) catch {
          case _: NoHostsFoundException => sys.error(
            if (hostsInStage.isEmpty)
              "No hosts found in stage %s; are you sure this is a valid stage?" format Config.stage
            else
              "No hosts matched requested deploy host %s. Run with --verbose to a see a list of possible hosts."
                format Config.host.getOrElse("")
          )
        }


        if (tasks.isEmpty)
          sys.error("No tasks were found to execute. Ensure the app(s) '%s' are in the list supported by this stage/host:\n%s." format (Resolver.possibleApps(project, Config.recipe), hosts.supportedApps))

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
          val credentials = if (Config.jvmSsh) {
            val passphrase = System.console.readPassword("Please enter your passphrase:")
            PassphraseProvided(System.getenv("USER"), passphrase.toString, Config.keyLocation)
          } else SystemUser(keyFile = Config.keyLocation)
          tasks.foreach { task =>
            Log.context("Executing %s..." format task.fullDescription) {
              task.execute(credentials)
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
}