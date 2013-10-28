package magenta
package cli

import java.io.File
import json.{DeployInfoJsonReader, JsonReader}
import scopt.OptionParser
import HostList._
import tasks.CommandLocator
import magenta.teamcity.Artifact._
import java.util.UUID
import org.joda.time.format.DateTimeFormat
import org.joda.time.DateTime
import scalax.file.Path
import scalax.file.ImplicitConversions.defaultPath2jfile
import scalax.file.ImplicitConversions.jfile2path
import scala.util.Try
import magenta.teamcity.Artifact
import java.net.URL

object Main extends scala.App {

  val sink = new MessageSink {
    var taskList: List[TaskDetail] = _

    def message(wrapper: MessageWrapper) {
      val indent = "  " * (wrapper.stack.messages.size - 1)
      wrapper.stack.top match {
        case Verbose(message) => if (Config.verbose) Console.out.println(indent + message)
        case TaskList(tasks) =>
          taskList = tasks
          Console.out.println(indent+"Tasks to execute: ")
          tasks.zipWithIndex.foreach { case (task, idx) =>
            Console.out.println(indent + "  %d. %s" format (idx + 1, task.fullDescription))
            if (Config.verbose) Console.out.println(indent + "  " + task.verbose)
          }
          Console.out.println()
        case StartContext(Info(message)) => { Console.out.println(indent + message) }
        case FinishContext(original) => {}
        case StartContext(TaskRun(task)) => {
          val timestamp = DateTimeFormat.mediumTime().print(new DateTime())
          Console.out.println("%s[%s] Starting task %d of %d: %s" format (indent, timestamp, taskList.indexOf(task)+1, taskList.length, task.fullDescription))
        }
        case _ => Console.out.println("%s%s" format (indent, wrapper.stack.top.text))
      }
    }
  }

  MessageBroker.subscribe(sink)

  object Config {

    var teamcityUrl: Option[URL] = Some(new URL("http://teamcity.guprod.gnm"))

    var project: Option[String] = None
    var build: Option[String] = None
    var host: Option[String] = None
    var deployer: Deployer = Deployer("unknown")
    var stage = ""
    var recipe = DefaultRecipe()
    var verbose = false
    var dryRun = false

    var keyLocation: Option[File] = None
    var jvmSsh = false

    var deployInfoExecutable = "/opt/bin/deployinfo.json"

    lazy val lookup = {
      import sys.process._
      DeployInfoJsonReader.parse(deployInfoExecutable.!!).asLookup
    }

    private var _localArtifactDir: Option[File] = None

    def localArtifactDir_=(dir: Option[File]) {
      dir.foreach { f =>
        if (!f.exists() || !f.isDirectory) sys.error("Directory not found.")
        MessageBroker.info("WARN: Ignoring <project> and <build>; using local artifact directory of " + f.getAbsolutePath)
      }

      _localArtifactDir = dir
    }

    def localArtifactDir = _localArtifactDir
  }

  object ManagementBuildInfo {
    lazy val version = Option(getClass.getPackage.getImplementationVersion) getOrElse "DEV"
  }

  val programName = "magenta"
  val programVersion = ManagementBuildInfo.version

  val parser = new OptionParser(programName, programVersion) {
    help("h", "help", "show this usage message")

    separator("\n  What to deploy:")
    opt("r", "recipe", "recipe to execute (default: 'default')", { r => Config.recipe = RecipeName(r) })
    opt("t", "host", "only deply to the named host", { h => Config.host = Some(h) })

    separator("\n  Diagnostic options:")
    opt("v", "verbose", "verbose logging", { Config.verbose = true } )
    opt("n", "dry-run", "don't execute any tasks, just show what would be done", { Config.dryRun = true })
    opt("deployer", "fullname or username of person executing the deployment", { name => Config.deployer = Deployer(name)})

    separator("\n  Advanced options:")
    opt("local-artifact", "Path to local artifact directory (overrides <project> and <build>)",
      { dir => Config.localArtifactDir = Some(new File(dir)) })
    opt("deployinfo", "use a different deployinfo script", { deployinfo => Config.deployInfoExecutable = deployinfo })
    opt("path", "Path for deploy support scripts (default: '/opt/deploy/bin')", { path => CommandLocator.rootPath = path })
    opt("i", "keyLocation", "specify location of SSH key file", {keyLocation => Config.keyLocation = Some(validFile(keyLocation))})
    opt("j", "jvm-ssh", "perform ssh within the JVM, rather than shelling out to do so", { Config.jvmSsh = true })

    separator("\n")
    opt("teamcityUrl", s"URL of the teamcity server from which to download artifacts (e.g. ${Config.teamcityUrl.toString}})",
      { teamcityUrl => Config.teamcityUrl = Some(new URL(teamcityUrl)) })
    arg("<stage>", "Stage to deploy (e.g. TEST)", { s => Config.stage = s })
    argOpt("<project>", "TeamCity project name (e.g. tools::stats-aggregator)", { p => Config.project = Some(p) })
    argOpt("<build>", "TeamCity build number", { b => Config.build = Some(b) })
  }

  def validFile(s: String) = {
    val file = new File(s)
    if (file.exists() && file.isFile) file else sys.error("File not found: %s" format (s))
  }

  def withTemporaryDirectory[T](block: File => T): T = {
    val tempDir = Path.createTempDirectory(prefix="magenta-", suffix="")
    val result = Try {
      block(tempDir)
    }
    tempDir.deleteRecursively(continueOnFailure = true)
    result.get
  }

  if (parser.parse(args)) {
    try {
      withTemporaryDirectory { tmpDir =>
        val build = Build(Config.project.get, Config.build.get)
        val parameters = DeployParameters(Config.deployer, build, Stage(Config.stage), Config.recipe, Config.host.toList)
        MessageBroker.deployContext(UUID.randomUUID(), parameters) {

          MessageBroker.info("[using %s build %s]" format (programName, programVersion))

          MessageBroker.info("Locating artifact...")

          Config.localArtifactDir.map{ file =>
            MessageBroker.info("Making temporary copy of local artifact: %s" format file)
            file.copyTo(Path(tmpDir))
          } getOrElse {
            Artifact.download(Config.teamcityUrl, tmpDir, build)
          }

          MessageBroker.info("Loading project file...")
          val project = JsonReader.parse(new File(tmpDir, "deploy.json"))

          MessageBroker.verbose("Loaded: " + project)

          val context = DeployContext(parameters,project,Config.lookup)

          if (Config.dryRun) {

            val tasks = context.tasks
            MessageBroker.info("Dry run requested. Not executing %d tasks." format tasks.size)

          } else {

            val credentials = if (Config.jvmSsh) {
              val passphrase = System.console.readPassword("Please enter your passphrase:")
              PassphraseProvided(System.getenv("USER"), passphrase.toString, Config.keyLocation)
            } else SystemUser(keyFile = Config.keyLocation)

            context.execute(KeyRing(credentials))
          }
        }
      }

      MessageBroker.info("Done")

    } catch {
      case e: UsageError =>
        Console.err.println("Error: " + e.getMessage)
        parser.showUsage
    }
  }
}