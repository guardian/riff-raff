package magenta
package tasks

import java.io.File

import com.amazonaws.services.s3.model.PutObjectRequest
import dispatch.classic._
import magenta.deployment_type.PatternValue

case class S3Upload(
  bucket: String,
  files: Seq[(File, String)],
  cacheControlPatterns: List[PatternValue] = Nil,
  extensionToMimeType: Map[String,String] = Map.empty,
  publicReadAcl: Boolean = false,
  detailedLoggingThreshold: Int = 10
)(implicit val keyRing: KeyRing) extends Task with S3 {

  lazy val flattenedFiles = files flatMap {
    case (file, key) => resolveFiles(file, key)
  }

  lazy val totalSize = flattenedFiles.map{ case (file, key) => file.length}.sum

  lazy val requests = flattenedFiles map {
    case (file, key) => S3.putObjectRequest(bucket, key, file, cacheControlLookup(key), contentTypeLookup(key), publicReadAcl)
  }

  def fileString(quantity: Int) = s"$quantity file${if (quantity != 1) "s" else ""}"

  // A verbose description of this task. For command line tasks,
  def verbose: String = s"$description using file mapping $files"

  // end-user friendly description of this task
  def description: String = s"Upload ${fileString(requests.size)} to S3 bucket $bucket"

  def requestToString(request: PutObjectRequest): String =
    s"${request.getFile.getPath} to s3://${request.getBucketName}/${request.getKey} with "+
      s"CacheControl:${request.getMetadata.getCacheControl} ContentType:${request.getMetadata.getContentType} ACL:${request.getCannedAcl}"

  // execute this task (should throw on failure)
  override def execute(reporter: DeployReporter, stopFlag: => Boolean) {
    val client = s3client(keyRing)
    reporter.verbose(s"Starting upload of ${fileString(files.size)} ($totalSize bytes) to S3 ${client}")
    requests.take(detailedLoggingThreshold).foreach(r => reporter.verbose(s"Uploading ${requestToString(r)}"))
    requests.par foreach { request => client.putObject(request) }
    reporter.verbose(s"Finished upload of ${fileString(files.size)} to S3")
  }

  private def subDirectoryPrefix(key: String, file:File): String = if (key.isEmpty) file.getName else s"$key/${file.getName}"
  private def resolveFiles(file: File, key: String): Seq[(File, String)] = {
    if (!file.isDirectory) Seq((file, key))
    else file.listFiles.toSeq.flatMap(f => resolveFiles(f, subDirectoryPrefix(key, f))).distinct
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

trait PollingCheck {
  def duration: Long

  def check(reporter: DeployReporter, stopFlag: => Boolean)(theCheck: => Boolean) {
    val expiry = System.currentTimeMillis() + duration

    def checkAttempt(currentAttempt: Int) {
      if (!theCheck) {
        if (stopFlag) {
          reporter.info("Abandoning remaining checks as stop flag has been set")
        } else {
          val remainingTime = expiry - System.currentTimeMillis()
          if (remainingTime > 0) {
            val sleepyTime = calculateSleepTime(currentAttempt)
            reporter.verbose("Check failed on attempt #%d (Will wait for a further %.1f seconds, retrying again after %.1fs)" format (currentAttempt, (remainingTime.toFloat/1000), (sleepyTime.toFloat/1000)))
            Thread.sleep(sleepyTime)
            checkAttempt(currentAttempt + 1)
          } else {
            reporter.fail("Check failed to pass within %d milliseconds (tried %d times) - aborting" format (duration,currentAttempt))
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
  override def execute(reporter: DeployReporter, stopFlag: => Boolean) {
    reporter.info("Hello to " + host.name + "!")
  }

  def description = "to " + host.name
  def verbose = fullDescription
}

case class ChangeSwitch(host: Host, protocol:String, port: Int, path: String, switchName: String, desiredState: Boolean)(implicit val keyRing: KeyRing) extends Task {
  val desiredStateName = if (desiredState) "ON" else "OFF"
  val switchboardUrl = s"$protocol://${host.name}:$port$path"

  // execute this task (should throw on failure)
  override def execute(reporter: DeployReporter, stopFlag: => Boolean) = {
    reporter.verbose(s"Changing $switchName to $desiredStateName using $switchboardUrl")
    Http(url(switchboardUrl) << Map(switchName -> desiredStateName) >|)
  }

  def verbose: String = s"$description using switchboard at $switchboardUrl"
  def description: String = s"$switchName to $desiredStateName"
}

case class UpdateLambda(
                   file: File,
                   functionName: String)
                 (implicit val keyRing: KeyRing) extends Task with Lambda {
  def description = s"Updating $functionName Lambda"
  def verbose = description

  override def execute(reporter: DeployReporter, stopFlag: => Boolean) {

    val client = lambdaClient(keyRing)
    reporter.verbose(s"Starting update $functionName Lambda")
    client.updateFunctionCode(lambdaUpdateFunctionCodeRequest(functionName, file))
    reporter.verbose(s"Finished update $functionName Lambda")
  }

}

case class UpdateS3Lambda(functionName: String, s3Bucket: String, s3Key: String)(implicit val keyRing: KeyRing) extends Task with Lambda {
  def description = s"Updating $functionName Lambda using S3 $s3Bucket:$s3Key"
  def verbose = description

  override def execute(reporter: DeployReporter, stopFlag: => Boolean) {
    val client = lambdaClient(keyRing)
    reporter.verbose(s"Starting update $functionName Lambda")
    client.updateFunctionCode(lambdaUpdateFunctionCodeRequest(functionName, s3Bucket, s3Key))
    reporter.verbose(s"Finished update $functionName Lambda")
  }

}

