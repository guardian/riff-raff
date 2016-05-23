package magenta
package tasks


import scala.io.Source
import java.net.Socket
import java.io.{IOException, FileNotFoundException, File}
import java.net.URL
import com.amazonaws.services.s3.model.PutObjectRequest
import magenta.deployment_type.PatternValue
import dispatch.classic._

object CommandLocator {
  var rootPath = "/opt/deploy/bin"
  def conditional(binary: String) = List("if", "[", "-f", rootPath+"/"+binary, "];", "then", rootPath+"/"+binary,";", "fi" )
}

object CopyFile {
  val ADDITIVE_MODE = "additive"
  val MIRROR_MODE = "mirror"
  lazy val MODES = List(ADDITIVE_MODE, MIRROR_MODE)
}
case class CopyFile(host: Host, source: String, dest: String, copyMode: String = CopyFile.ADDITIVE_MODE)
                   (implicit val keyRing: KeyRing) extends ShellTask {
  override val taskHost = Some(host)
  val noHostKeyChecking = "-o" :: "UserKnownHostsFile=/dev/null" :: "-o" :: "StrictHostKeyChecking=no" :: Nil

  def commandLine = {
    val rsyncOptions = copyMode match {
      case CopyFile.ADDITIVE_MODE => List("-rpv")
      case CopyFile.MIRROR_MODE => List("-rpv", "--delete", "--delete-after")
      case _ => throw new IllegalArgumentException(s"Unknown copyMode: $copyMode (use one of ${CopyFile.MODES.mkString(",")})")
    }
    "rsync" :: rsyncOptions ::: source :: s"${host.connectStr}:$dest" :: Nil
  }
  def commandLine(keyRing: KeyRing): CommandLine = {
    val keyFileArgs = keyRing.sshCredentials.keyFile.toList.flatMap("-i" :: _.getPath :: Nil)
    val shellCommand = CommandLine("ssh" :: noHostKeyChecking ::: keyFileArgs ::: Nil).quoted
    CommandLine(commandLine.commandLine.head :: "-e" :: shellCommand :: commandLine.commandLine.tail)
  }

  lazy val description = "%s -> %s:%s" format (source, host.connectStr, dest)

  override def execute(stopFlag: =>  Boolean) {
    commandLine(keyRing).run()
  }
}

case class CompressedCopy(host: Host, source: Option[File], dest: String)(implicit val keyRing: KeyRing)
  extends CompositeTask with CompressedFilename {

  val tasks = Seq(
    Compress(source),
    CopyFile(host, if (source.isEmpty) "unknown at preview time" else compressedPath, dest),
    Decompress(host, dest, source)
  )

  def description: String = "%s -> %s:%s using a compressed archive while copying to the target" format
    (source.getOrElse("Unknown"), host.connectStr, dest)

  def verbose: String = description

  override val taskHost = Some(host)
}

trait CompositeTask extends Task {
  def tasks: Seq[Task]
  def execute(stopFlag: => Boolean) {
    for (task <- tasks) { if (!stopFlag) task.execute(stopFlag) }
  }
}


case class Compress(source:  Option[File])(implicit val keyRing: KeyRing) extends ShellTask with CompressedFilename {
  def commandLine: CommandLine = {
    CommandLine("tar" :: "--bzip2" :: "--directory" :: sourceFile.getParent :: "-cf" :: compressedPath :: sourceFile.getName :: Nil)
  }

  def description: String = "Compress %s to %s" format (source, compressedName)
}

case class Decompress(host: Host, dest: String, source: Option[File])(implicit val keyRing: KeyRing) extends RemoteShellTask with CompressedFilename {
  def commandLine: CommandLine = {
    CommandLine("tar" :: "--bzip2" :: "--directory" :: dest :: "-xmf" :: dest + compressedName:: Nil)
  }
}

case class DecompressTarBall(host: Host, tarball: String, dest: String)(implicit val keyRing: KeyRing) extends RemoteShellTask {
  def commandLine: CommandLine = {
    CommandLine("tar" :: "--directory" :: dest :: "-xmzf" :: tarball :: Nil)
  }
}

trait CompressedFilename {
  def source: Option[File]
  def sourceFile = source.getOrElse(throw new FileNotFoundException())
  def compressedPath = sourceFile.getPath + ".tar.bz2"
  def compressedName = sourceFile.getName + ".tar.bz2"
}

case class S3Upload(
  bucket: String,
  files: Seq[(File, String)],
  cacheControlPatterns: List[PatternValue] = Nil,
  extensionToMimeType: Map[String,String] = Map.empty,
  publicReadAcl: Boolean = false,
  detailedLoggingThreshold: Int = 10
)(implicit val keyRing: KeyRing) extends Task with S3 {

  lazy val totalSize = files.map{ case (file, key) => file.length}.sum

  lazy val flattenedFiles = files flatMap {
    case (file, key) => resolveFiles(file, key)
  }

  lazy val requests = flattenedFiles map {
    case (file, key) => S3.putObjectRequest(bucket, key, file, cacheControlLookup(key), contentTypeLookup(key), publicReadAcl)
  }

  // A verbose description of this task. For command line tasks,
  def verbose: String = s"$description using file mapping $files"

  // end-user friendly description of this task
  def description: String = s"Upload ${requests.size} files to S3 bucket $bucket"

  def requestToString(request: PutObjectRequest): String =
    s"${request.getFile.getPath} to s3://${request.getBucketName}/${request.getKey} with "+
      s"CacheControl:${request.getMetadata.getCacheControl} ContentType:${request.getMetadata.getContentType} ACL:${request.getCannedAcl}"

  // execute this task (should throw on failure)
  def execute(stopFlag: =>  Boolean)  {
    val client = s3client(keyRing)
    MessageBroker.verbose(s"Starting upload of ${files.size} files ($totalSize bytes) to S3")
    requests.par foreach { request =>
      client.putObject(request)
      if (requests.length <= detailedLoggingThreshold) MessageBroker.verbose(s"Uploaded ${requestToString(request)}")
    }
    MessageBroker.verbose(s"Finished upload of ${files.size} files to S3")
  }

  private def resolveFiles(file: File, key: String): Seq[(File, String)] = {
    if (!file.isDirectory) Seq((file, key))
    else file.listFiles.toSeq.flatMap(f => resolveFiles(f, s"$key/${f.getName}")).distinct
  }

  private def contentTypeLookup(fileName: String) = fileExtension(fileName).flatMap(extensionToMimeType.get)
  private def cacheControlLookup(fileName:String) = cacheControlPatterns.find(_.regex.findFirstMatchIn(fileName).isDefined).map(_.value)
  private def fileExtension(fileName: String) = fileName.split('.').drop(1).lastOption
}

object S3Upload {
  def prefixGenerator(stack:Option[Stack] = None, stage:Option[Stage] = None, packageName:Option[String] = None): String = {
    (stack.flatMap(_.nameOption) :: stage.map(_.name) :: packageName :: Nil).flatten.mkString("/")
  }
  def prefixGenerator(stack: Stack, stage: Stage, packageName: String): String =
    prefixGenerator(Some(stack), Some(stage), Some(packageName))
}

case class BlockFirewall(host: Host)(implicit val keyRing: KeyRing) extends RemoteShellTask {
  def commandLine = CommandLocator conditional "block-load-balancer"
}

case class Service(host: Host, appName: String, command: String = "restart")(implicit val keyRing: KeyRing) extends RemoteShellTask {
  def commandLine = List("sudo", "/sbin/service", appName, command)

  override lazy val description = s" of $appName using $command command"
}

case class UnblockFirewall(host: Host)(implicit val keyRing: KeyRing) extends RemoteShellTask {
  def commandLine =  CommandLocator conditional "unblock-load-balancer"
}

case class WaitForPort(host: Host, port: Int, duration: Long)(implicit val keyRing: KeyRing) extends Task with RepeatedPollingCheck {
  override def taskHost = Some(host)
  def description = "to %s on %s" format(host.name, port)
  def verbose = "Wail until a socket connection can be made to %s:%s" format(host.name, port)

  def execute(stopFlag: =>  Boolean) {
    check(stopFlag) {
      try {
        new Socket(host.name, port).close()
        true
      } catch {
        case e: IOException => false
      }
    }
  }
}

case class CheckUrls(host: Host, port: Int, paths: List[String], duration: Long, checkUrlReadTimeoutSeconds: Int)(implicit val keyRing: KeyRing)
    extends Task with RepeatedPollingCheck {
  override def taskHost = Some(host)
  def description = "check [%s] on %s" format(paths, host)
  def verbose = "Check that [%s] returns a 200" format(paths)

  def execute(stopFlag: =>  Boolean) {
    for (path <- paths) {
      val url = new URL( "http://%s:%s%s" format (host.connectStr, port, path) )
      MessageBroker.verbose("Checking %s" format url)
      check(stopFlag) {
        try {
          val connection = url.openConnection()
          connection.setConnectTimeout( 2000 )
          connection.setReadTimeout( checkUrlReadTimeoutSeconds * 1000 )
          Source.fromInputStream( connection.getInputStream )
          true
        } catch {
          case e: FileNotFoundException => MessageBroker.fail("404 Not Found", e)
          case e:Throwable => false
        }
      }
    }
  }
}

trait PollingCheck {
  def duration: Long

  def check(stopFlag: => Boolean)(theCheck: => Boolean) {
    val expiry = System.currentTimeMillis() + duration

    def checkAttempt(currentAttempt: Int) {
      if (!theCheck) {
        if (stopFlag) {
          MessageBroker.info("Abandoning remaining checks as stop flag has been set")
        } else {
          val remainingTime = expiry - System.currentTimeMillis()
          if (remainingTime > 0) {
            val sleepyTime = calculateSleepTime(currentAttempt)
            MessageBroker.verbose("Check failed on attempt #%d (Will wait for a further %.1f seconds, retrying again after %.1fs)" format (currentAttempt, (remainingTime.toFloat/1000), (sleepyTime.toFloat/1000)))
            Thread.sleep(sleepyTime)
            checkAttempt(currentAttempt + 1)
          } else {
            MessageBroker.fail("Check failed to pass within %d milliseconds (tried %d times) - aborting" format (duration,currentAttempt))
          }
        }
      }
    }
    checkAttempt(1)
  }

  def calculateSleepTime(currentAttempt: Int): Long
}

trait RepeatedPollingCheck extends PollingCheck {

  def calculateSleepTime(currentAttempt: Int): Long = {
    val exponent = math.min(currentAttempt, 8)
    math.min(math.pow(2,exponent).toLong*100, 25000)
  }
}

trait SlowRepeatedPollingCheck extends PollingCheck {

  def calculateSleepTime(currentAttempt: Int): Long = 30000
}


case class SayHello(host: Host)(implicit val keyRing: KeyRing) extends Task {
  override def taskHost = Some(host)
  def execute(stopFlag: => Boolean) {
    MessageBroker.info("Hello to " + host.name + "!")
  }

  def description = "to " + host.name
  def verbose = fullDescription
}

case class EchoHello(host: Host)(implicit val keyRing: KeyRing) extends ShellTask {
  override def taskHost = Some(host)
  def commandLine = List("echo", "hello to " + host.name)
  def description = "to " + host.name
}

case class Link(host: Host, target: Option[String], linkName: String)(implicit val keyRing: KeyRing) extends RemoteShellTask {
  def commandLine = List("ln", "-sfn", target.getOrElse(throw new FileNotFoundException()), linkName)
}

case class ApacheGracefulRestart(host: Host)(implicit val keyRing: KeyRing) extends RemoteShellTask {
  def commandLine = List("sudo", "/usr/sbin/apachectl", "graceful")
}

case class Mkdir(host: Host, path: String)(implicit val keyRing: KeyRing) extends RemoteShellTask {
	def commandLine = List("/bin/mkdir", "-p", path)
}

case class deleteCompressedFiles(host: Host, path: String)(implicit val keyRing: KeyRing) extends RemoteShellTask {
  def commandLine = List("rm", "-rf", s"$path*.tar.bz2")
}

case class deleteOldDeploys(host: Host, amount: Int, path: String, prefix: String)(implicit val keyRing: KeyRing) extends RemoteShellTask {
  def commandLine = List("ls", "-tdr", s"$path$prefix?*", "|", "head", "-n", s"-$amount", "|", "xargs", "-t", "-n1", "rm", "-rf")
}

case class CleanupOldDeploys(host: Host, amount: Int = 0, path: String, prefix: String)(implicit val keyRing: KeyRing) extends CompositeTask {

  val tasks = if (amount > 0) Seq( deleteCompressedFiles(host, path), deleteOldDeploys(host, amount, path, prefix) ) else Seq.empty

  def description: String = "Cleanup old deploys "

  def verbose: String = description

  override val taskHost = Some(host)

}

case class RemoveFile(host: Host, path: String, recursive: Boolean = false)(implicit val keyRing: KeyRing) extends RemoteShellTask {
  def conditional(test: List[String], command: List[String]) = List("if", "[") ++ test ++ List("];", "then") ++ command ++ List(";", "fi" )
  val recursiveFlag = if (recursive) List("-r") else Nil
  def commandLine = conditional(
    List("-e", path),
    List("/bin/rm") ++ recursiveFlag :+ path
  )
  override lazy val description = s"$path from ${host.name} (recursion=$recursive)"
}

case class ChangeSwitch(host: Host, protocol:String, port: Int, path: String, switchName: String, desiredState: Boolean)(implicit val keyRing: KeyRing) extends Task {
  val desiredStateName = if (desiredState) "ON" else "OFF"
  val switchboardUrl = s"$protocol://${host.name}:$port$path"

  // execute this task (should throw on failure)
  def execute(stopFlag: => Boolean) = {
    MessageBroker.verbose(s"Changing $switchName to $desiredStateName using $switchboardUrl")
    Http(url(switchboardUrl) << Map(switchName -> desiredStateName) >|)
  }

  def verbose: String = s"$description using switchboard at $switchboardUrl"
  def description: String = s"$switchName to $desiredStateName"
}

case class InstallRpm(host: Host, path: String, noFileDigest: Boolean = false)(implicit val keyRing: KeyRing) extends RemoteShellTask {
  val extraFlags = if (noFileDigest) List("--nofiledigest") else Nil
  def commandLine = List("sudo", "/bin/rpm", "-U", "--oldpackage", "--replacepkgs") ++ extraFlags :+ path
  override lazy val description = s"$path on ${host.name}"
}

case class UpdateLambda(
                   file: File,
                   functionName: String)
                 (implicit val keyRing: KeyRing) extends Task with Lambda {
  def description = s"Updating $functionName Lambda"
  def verbose = description

  def execute(stopFlag: =>  Boolean) {

    val client = lambdaClient(keyRing)
    MessageBroker.verbose(s"Starting update $functionName Lambda")
    client.updateFunctionCode(lambdaUpdateFunctionCodeRequest(functionName, file))
    MessageBroker.verbose(s"Finished update $functionName Lambda")
  }

}

case class UpdateS3Lambda(functionName: String, s3Bucket: String, s3Key: String)(implicit val keyRing: KeyRing) extends Task with Lambda {
  def description = s"Updating $functionName Lambda using S3 $s3Bucket:$s3Key"
  def verbose = description

  def execute(stopFlag: =>  Boolean) {
    val client = lambdaClient(keyRing)
    MessageBroker.verbose(s"Starting update $functionName Lambda")
    client.updateFunctionCode(lambdaUpdateFunctionCodeRequest(functionName, s3Bucket, s3Key))
    MessageBroker.verbose(s"Finished update $functionName Lambda")
  }

}

