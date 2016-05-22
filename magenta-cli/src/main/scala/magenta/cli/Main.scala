package magenta
package cli

import java.io.File
import java.net.URL
import java.util.UUID

import com.amazonaws.auth._
import com.amazonaws.services.s3.AmazonS3Client
import magenta.artifact.S3Artifact
import magenta.json.{DeployInfoJsonReader, JsonReader}
import magenta.tasks.CommandLocator
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import scopt.OptionParser

import scala.util.Try
import scalax.file.ImplicitConversions.{defaultPath2jfile, jfile2path}
import scalax.file.Path

object Main extends scala.App {

  var taskList: List[TaskDetail] = _
  DeployLogger.messages.subscribe(wrapper => {
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
        System.err.println("WARN: Ignoring <project> and <build>; using local artifact directory of " + f.getAbsolutePath)
      }

      _localArtifactDir = dir
    }

    def localArtifactDir = _localArtifactDir

    var bucketName: Option[String] = None
    var accessKey: Option[String] = None
    var secretKey: Option[String] = None
    lazy val credentialsProvider =
      new AWSCredentialsProviderChain(new AWSCredentialsProvider {
        override def getCredentials: AWSCredentials = (for {
          key <- accessKey
          secret <- secretKey
        } yield new BasicAWSCredentials(key, secret)).getOrElse(null)

        override def refresh(): Unit = {}
      }, new DefaultAWSCredentialsProviderChain())
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
    opt[String]("bucketName") text (s"S3 Bucket to download artifacts from") foreach
      { bucketName => Config.bucketName = Some(bucketName) }
    opt[String]("accessKey") text (s"Access Key for downloading artifacts from S3") foreach
      { accessKey => Config.accessKey = Some(accessKey) }
    opt[String]("secretKey") text (s"Secret Key for downloading artifacts from S3") foreach
        { secretKey => Config.secretKey = Some(secretKey) }

    arg[String]("<stage>") text ("Stage to deploy (e.g. TEST)") foreach { s => Config.stage = s }
    arg[String]("<project>") text ("Project name (e.g. tools::stats-aggregator)") foreach { p => Config.project = Some(p) }
    arg[String]("<build>") text ("Build number") foreach { b => Config.build = Some(b) }
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
        val deployId = UUID.randomUUID()
        implicit val rootLogger = DeployLogger.rootLoggerFor(deployId, parameters)
        rootLogger.info("[using %s build %s]" format (programName, programVersion))

        rootLogger.info("Locating artifact...")

        Config.localArtifactDir.map{ file =>
          rootLogger.info("Making temporary copy of local artifact: %s" format file)
          file.copyTo(Path(tmpDir))
        } getOrElse {
          S3Artifact.download(build, tmpDir)(Config.bucketName, new AmazonS3Client(
            new AWSCredentialsProviderChain(new AWSCredentialsProvider {
              override def getCredentials: AWSCredentials = (for {
                key <- Config.accessKey
                secret <- Config.secretKey
              } yield new BasicAWSCredentials(key, secret)).getOrElse(null)

              override def refresh(): Unit = {}
            }, new DefaultAWSCredentialsProviderChain())
          ), rootLogger)
        }

        rootLogger.info("Loading project file...")
        val project = JsonReader.parse(new File(tmpDir, "deploy.json"))

        rootLogger.verbose("Loaded: " + project)

        val context = DeployContext(deployId, parameters, project, Config.lookup, rootLogger)

        if (Config.dryRun) {

          val tasks = context.tasks
          rootLogger.info("Dry run requested. Not executing %d tasks." format tasks.size)

        } else {

          context.execute()
        }
        rootLogger.info("Done")
      }


    } catch {
      case e: UsageError =>
        Console.err.println("Error: " + e.getMessage)
        parser.showUsage
    }
  }
}