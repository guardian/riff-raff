package magenta
package cli

import java.io.File
import json.{DeployInfoJsonHostProvider, JsonReader}
import scopt.OptionParser
import tasks.CommandLocator
import HostList._
import com.decodified.scalassh.PublicKeyLogin.DefaultKeyLocations
import com.decodified.scalassh.{SshLogin, SimplePasswordProducer, PublicKeyLogin}
import host.EC2HostProvider

object Main extends scala.App {

  object Config {
    var project: Option[String] = None

    var build: Option[String] = None

    var host: Option[String] = None
    var stage = ""
    var recipe = "default"
    var verbose = false
    var dryRun = false
    var ec2Hosts = false

    var jvmSsh = false
    var keyLocation: Option[String] = None
    var noPassphrase = false

    private var _di = "/opt/bin/deployinfo.json"

    def deployInfo_=(s: String) {
      val f = new File(s)
      if (!f.exists() || !f.isFile) sys.error("File not found.")
      _di = s
    }
    def deployInfo = _di

    lazy val hostList = {
      if (ec2Hosts) {
        new EC2HostProvider().hosts
      } else {
        import sys.process._
        new DeployInfoJsonHostProvider(_di.!!).hosts
      }
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
    opt("ec2-hosts", "Retrieve host list from EC2", { Config.ec2Hosts = true})
    opt("path", "Path for deploy support scripts (default: '/opt/deploy/bin')",
      { path => CommandLocator.rootPath = path })

    opt("j", "jvm-ssh", "perform ssh within the JVM, rather than shelling out to do so",
      { Config.jvmSsh = true })
    opt("k", "key-location", "location of SSH key used for authentication. Only works with JVM based SSH",
      { location => Config.keyLocation = Some(location)})
    opt("no-passphrase", "use a passphrase-less SSH key. Only works with JVM based SSH",
      {Config.noPassphrase = true})

    separator("\n")

    arg("<stage>", "Stage to deploy (e.g. TEST)", { s => Config.stage = s })
    argOpt("<project>", "TeamCity project name (e.g. tools::stats-aggregator)", { p => Config.project = Some(p) })
    argOpt("<build>", "TeamCity build number", { b => Config.build = Some(b) })

  }

  lazy val sshCredentials: Option[PublicKeyLogin] = if (Config.jvmSsh) {
    val passphrase = if (Config.noPassphrase) None else Some(System.console.readPassword("Please enter your passphrase:"))
    Some(PublicKeyLogin(System.getenv("USER"),
      passphrase map { phrase => SimplePasswordProducer(phrase.toString) },
      Config.keyLocation.map(List(_)).getOrElse(DefaultKeyLocations)))
  } else None

  Log.current.withValue(CommandLineOutput) {
    if (parser.parse(args)) {
      try {
        Log.info("%s build %s" format (programName, programVersion))

        Log.info("Locating artifact...")
        val dir = Config.localArtifactDir getOrElse Artifact.download(Config.project, Config.build)

        Log.info("Loading project file...")
        val project = JsonReader.parse(new File(dir, "deploy.json"))

        Log.verbose("Loaded: " + project)

        Log.info("Loading host list...")
        val hostsInStage = Config.hostList.filter(_.stage == Config.stage)

        Log.verbose("All possible hosts in stage:\n" + hostsInStage.dump)

        if (hostsInStage.isEmpty)
          sys.error("No hosts found in stage %s; are you sure this is a valid stage?" format Config.stage)

        val hosts = Config.host map { h => hostsInStage.filter(_.name == h) } getOrElse hostsInStage

        if (hosts.isEmpty)
          sys.error("No hosts matched requested deploy host %s. Run with --verbose to a see a list of possible hosts." format Config.host.getOrElse(""))

        Log.info("Resolving...")
        val tasks = Resolver.resolve(project, Config.recipe, hosts, Stage(Config.stage))

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
          tasks.foreach { task =>
            Log.context("Executing %s..." format task.fullDescription) {
              task.execute(sshCredentials)
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