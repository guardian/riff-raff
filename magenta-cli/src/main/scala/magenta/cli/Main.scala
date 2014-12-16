package magenta
package cli

import java.io.File
import json.{DeployInfoJsonReader, JsonReader}
import scopt.{Zero, OptionParser}
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

  var taskList: List[TaskDetail] = _
  MessageBroker.messages.subscribe(wrapper => {
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
  })

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
      val deployInfo = DeployInfoJsonReader.parse(deployInfoExecutable.!!)
      DeployInfoLookupShim(
        deployInfo,
        new SecretProvider {
          def lookup(service: String, account: String): Option[String] =
            Some(System.console.readPassword(s"Secret required to continue with deploy\nPlease enter secret for $service account $account:").toString)
          def sshCredentials = if (Config.jvmSsh) {
            val passphrase = System.console.readPassword("Please enter your passphrase:")
            PassphraseProvided(System.getenv("USER"), passphrase.toString, Config.keyLocation)
          } else SystemUser(keyFile = Config.keyLocation)
        }
      )
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

  val parser = new OptionParser[Unit](programName) {
    head(programName, programVersion)

    help("help") abbr("h") text ("show this usage message")

    note("\n  What to deploy:")

    opt[String]('r', "recipe") text ("recipe to execute (default: 'default')") foreach { r => Config.recipe = RecipeName(r) }
    opt[String]('t', "host") text ("only deply to the named host") foreach { h => Config.host = Some(h) }

    note("\n  Diagnostic options:")
    opt[Unit]('v', "verbose") text ("verbose logging") foreach { _ => Config.verbose = true }
    opt[Unit]('n', "dry-run") text ("don't execute any tasks, just show what would be done") foreach { _ => Config.dryRun = true }
    opt[String]("deployer") text ("fullname or username of person executing the deployment") foreach
      { name => Config.deployer = Deployer(name)}

    note("\n  Advanced options:")
    opt[String]("local-artifact") text ("Path to local artifact directory (overrides <project> and <build>)") foreach
      { dir => Config.localArtifactDir = Some(new File(dir)) }
    opt[String]("deployinfo") text ("use a different deployinfo script") foreach
      { deployinfo => Config.deployInfoExecutable = deployinfo }
    opt[String]("path") text ("Path for deploy support scripts (default: '/opt/deploy/bin')") foreach
      { path => CommandLocator.rootPath = path }
    opt[String]('i', "keyLocation") text ("specify location of SSH key file") foreach
      {keyLocation => Config.keyLocation = Some(validFile(keyLocation))}
    opt[Unit]('j', "jvm-ssh") text ("perform ssh within the JVM, rather than shelling out to do so") foreach
      { _ => Config.jvmSsh = true }

    note("\n")
    opt[String]("teamcityUrl") text (s"URL of the teamcity server from which to download artifacts (e.g. ${Config.teamcityUrl.toString}})") foreach
      { teamcityUrl => Config.teamcityUrl = Some(new URL(teamcityUrl)) }
    arg[String]("<stage>") text ("Stage to deploy (e.g. TEST)") foreach { s => Config.stage = s }
    arg[String]("<project>") text ("TeamCity project name (e.g. tools::stats-aggregator)") foreach { p => Config.project = Some(p) }
    arg[String]("<build>") text ("TeamCity build number") foreach { b => Config.build = Some(b) }
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
        val parameters = DeployParameters(Config.deployer, build, Stage(Config.stage), Config.recipe, Nil, Config.host.toList)
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

            context.execute()
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