package magenta
package tasks

import scala.io.Source
import java.net.Socket
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.CannedAccessControlList.PublicRead
import scala._
import java.io.{IOException, FileNotFoundException, File}
import com.amazonaws.services.s3.model.{ObjectMetadata, PutObjectRequest}
import java.net.URL
import magenta.deployment_type.PatternValue

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
    CommandLine("tar" :: "--bzip2" :: "--directory" :: dest :: "-xf" :: dest + compressedName:: Nil)
  }
}

trait CompressedFilename {
  def source: Option[File]
  def sourceFile = source.getOrElse(throw new FileNotFoundException())
  def compressedPath = sourceFile.getPath + ".tar.bz2"
  def compressedName = sourceFile.getName + ".tar.bz2"
}


case class S3Upload( stack: Stack,
                     stage: Stage,
                     bucket: String,
                     file: File,
                     cacheControlPatterns: List[PatternValue] = Nil,
                     prefixStack: Boolean = true,
                     prefixStage: Boolean = true,
                     prefixPackage: Boolean = true,
                     publicReadAcl: Boolean = true)
                   (implicit val keyRing: KeyRing) extends Task with S3 {

  private val base = if (prefixPackage) file.getParent + "/" else file.getPath + "/"

  private val describe = {
    val fileDesc = if (file.isDirectory) "directory" else "file"
    val aclDesc = if (publicReadAcl) " with public read ACL" else ""
    s"Upload $fileDesc $file to S3$aclDesc"
  }

  def description = describe
  def verbose = describe

  lazy val filesToCopy = resolveFiles(file)

  lazy val totalSize = filesToCopy.map(_.length).sum

  lazy val requests = filesToCopy map { file =>
    S3.putObjectRequest(bucket, toKey(file), file, cacheControlLookup(toRelative(file)), publicReadAcl)
  }

  def execute(stopFlag: =>  Boolean)  {
    val client = s3client(keyRing)
    MessageBroker.verbose("Starting upload of %d files (%d bytes) to S3" format (requests.size, totalSize))
    requests.par foreach { client.putObject }
    MessageBroker.verbose("Finished upload of %d files to S3" format requests.size)
  }

  def toRelative(file: File) = file.getAbsolutePath.replace(base, "")
  def toKey(file: File) = {
    val stageName = if (prefixStage) stage.name + "/" else ""
    val stackName = stack match {
      case NamedStack(s) if prefixStack => s + "/"
      case _ => ""
    }
    s"$stackName$stageName${toRelative(file)}"
  }

  def cacheControlLookup(fileName:String) = cacheControlPatterns.find(_.regex.findFirstMatchIn(fileName).isDefined).map(_.value)

  private def resolveFiles(file: File): Seq[File] =
    Option(file.listFiles).map { _.toSeq.flatMap(resolveFiles) } getOrElse (Seq(file)).distinct
}

case class BlockFirewall(host: Host)(implicit val keyRing: KeyRing) extends RemoteShellTask {
  def commandLine = CommandLocator conditional "block-load-balancer"
}

case class Restart(host: Host, appName: String, command: String = "restart")(implicit val keyRing: KeyRing) extends RemoteShellTask {
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

case class deleteOldDeploys(host: Host, amount: Int, path: String)(implicit val keyRing: KeyRing) extends RemoteShellTask {
  val toDelete = amount + 1
  def commandLine = List("ls", "-t", """--ignore="logs"""", path, "|", "head", "-n", s"-$toDelete", "|", "xargs", "-x", "rm", "-rf")
}

case class CleanupOldDeploys(host: Host, amount: Int = 0, path: String)(implicit val keyRing: KeyRing) extends CompositeTask {

  val tasks = if (amount > 0) Seq( deleteCompressedFiles(host, path), deleteOldDeploys(host, amount, path) ) else Seq.empty

  def description: String = "Cleanup old deploys "

  def verbose: String = description

  override val taskHost = Some(host)

}

case class RemoveFile(host: Host, path: String)(implicit val keyRing: KeyRing) extends RemoteShellTask {
  def commandLine = List("/bin/rm", path)
  override lazy val description = s"$path from ${host.name}"
}

case class InstallRpm(host: Host, path: String, noFileDigest: Boolean = false)(implicit val keyRing: KeyRing) extends RemoteShellTask {
  val extraFlags = if (noFileDigest) List("--nofiledigest") else Nil
  def commandLine = List("sudo", "/bin/rpm", "-U", "--oldpackage", "--replacepkgs") ++ extraFlags :+ path
  override lazy val description = s"$path on ${host.name}"
}
