package magenta
package tasks

import java.io.File

import com.gu.management.Loggable
import magenta.artifact._
import magenta.deployment_type.{LambdaFunction, LambdaFunctionName, LambdaFunctionTags}
import magenta.deployment_type.param_reads.PatternValue
import okhttp3._
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.internal.util.Mimetype
import software.amazon.awssdk.core.sync.{RequestBody => AWSRequestBody}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, ObjectCannedACL, PutObjectRequest}

import scala.util.control.NonFatal

case class S3Upload(
  region: Region,
  bucket: String,
  paths: Seq[(S3Location, String)],
  cacheControlPatterns: List[PatternValue] = Nil,
  surrogateControlPatterns: List[PatternValue] = Nil,
  extensionToMimeType: Map[String,String] = Map.empty,
  publicReadAcl: Boolean = false,
  detailedLoggingThreshold: Int = 10,
)(implicit val keyRing: KeyRing, artifactClient: S3Client,
  withClientFactory: (KeyRing, Region, ClientOverrideConfiguration, DeploymentResources) => (S3Client => Unit) => Unit = S3.withS3client[Unit]) extends Task with Loggable {

  lazy val objectMappings: Seq[(S3Object, S3Path)] = paths flatMap {
    case (file, targetKey) => resolveMappings(file, targetKey, bucket)
  }

  lazy val totalSize: Long = objectMappings.map{ case (source, _) => source.size }.sum

  lazy val requests: Seq[PutReq] = objectMappings.map { case (source, target) =>
    PutReq(source, target, cacheControlLookup(target.key), surrogateControlLookup(target.key), contentTypeLookup(target.key), publicReadAcl)
  }

  def fileString(quantity: Int) = s"$quantity file${if (quantity != 1) "s" else ""}"

  // end-user friendly description of this task
  def description: String = s"Upload ${fileString(objectMappings.size)} to S3 bucket $bucket using file mapping $paths"

  def requestToString(source: S3Object, request: PutReq): String =
    s"s3://${source.bucket}/${source.key} to s3://${request.target.bucket}/${request.target.key} with "+
      s"CacheControl:${request.cacheControl} ContentType:${request.contentType} PublicRead:${request.publicReadAcl}"

  // execute this task (should throw on failure)
  override def execute(resources: DeploymentResources, stopFlag: => Boolean) {
    if (totalSize == 0) {
      val locationDescription = paths.map {
        case (path: S3Path, _) => path.show()
        case (location, _) => location.toString
      }.mkString("\n")
      resources.reporter.fail(s"No files found to upload in $locationDescription")
    }

    val withClient = withClientFactory(keyRing, region, AWS.clientConfigurationNoRetry, resources)
    withClient { client =>

      resources.reporter.verbose(s"Starting transfer of ${fileString(objectMappings.size)} ($totalSize bytes)")
      requests.zipWithIndex.par.foreach { case (req, index) =>
        logger.debug(s"Transferring ${requestToString(req.source, req)}")
        index match {
          case x if x < 10 => resources.reporter.verbose(s"Transferring ${requestToString(req.source, req)}")
          case 10 => resources.reporter.verbose(s"Not logging details for the remaining ${fileString(objectMappings.size - 10)}")
          case _ =>
        }
        retryOnException(AWS.clientConfiguration) {
          val copyObjectRequest = GetObjectRequest.builder()
            .bucket(req.source.bucket)
            .key(req.source.key)
            .build()
          val inputStream = resources.artifactClient.getObjectAsBytes(copyObjectRequest).asByteArray()
          val requestBody = AWSRequestBody.fromBytes(inputStream)

          val putRequest: PutObjectRequest = req.toAwsRequest
          val result = client.putObject(putRequest, requestBody)
          logger.debug(s"Put object ${putRequest.key}: MD5: ${result.sseCustomerKeyMD5} Metadata: ${result.responseMetadata}")
          result
        }
      }
    }
    resources.reporter.verbose(s"Finished transfer of ${fileString(objectMappings.size)}")
  }

  private def subDirectoryPrefix(key: String, fileName: String): String =
    if (fileName.isEmpty)
      key
    else if (key.isEmpty)
      fileName
    else s"$key/$fileName"

  private def resolveMappings(path: S3Location, targetKey: String, targetBucket: String): Seq[(S3Object, S3Path)] = {
    path.listAll()(artifactClient).map { obj =>
      obj -> S3Path(targetBucket, subDirectoryPrefix(targetKey, obj.relativeTo(path)))
    }
  }

  private def contentTypeLookup(fileName: String) = fileExtension(fileName).flatMap(extensionToMimeType.get)
  private def cacheControlLookup(fileName:String) = cacheControlPatterns.find(_.regex.findFirstMatchIn(fileName).isDefined).map(_.value)
  private def surrogateControlLookup(fileName:String) = surrogateControlPatterns.find(_.regex.findFirstMatchIn(fileName).isDefined).map(_.value)
  private def fileExtension(fileName: String) = fileName.split('.').drop(1).lastOption
}

object S3Upload {
  private val mimeTypes = Mimetype.getInstance()

  def awsMimeTypeLookup(fileName: String): String = mimeTypes.getMimetype(new File(fileName))

  def prefixGenerator(stack:Option[Stack] = None, stage:Option[Stage] = None, packageName:Option[String] = None): String = {
    (stack.map(_.name) :: stage.map(_.name) :: packageName :: Nil).flatten.mkString("/")
  }
  def prefixGenerator(stack: Stack, stage: Stage, packageName: String): String =
    prefixGenerator(Some(stack), Some(stage), Some(packageName))
}

case class PutReq(source: S3Object, target: S3Path, cacheControl: Option[String], surrogateControl: Option[String], contentType: Option[String], publicReadAcl: Boolean) {
  import collection.JavaConverters._

  type ReqModifier = PutObjectRequest.Builder => PutObjectRequest.Builder
  private val setCacheControl: ReqModifier = r => cacheControl.map(cc => r.cacheControl(cc)).getOrElse(r)
  private val setsurrogateControl: ReqModifier = r => surrogateControl.map(scc =>
    r.metadata(Map[String,String]("surrogate-control" -> scc).asJava)
  ).getOrElse(r)

  def toAwsRequest: PutObjectRequest = {
    val req = PutObjectRequest.builder()
      .bucket(target.bucket)
      .key(target.key)
      .contentType(contentType.getOrElse(S3Upload.awsMimeTypeLookup(target.key)))
      .contentLength(source.size)
    val reqWithCacheControl = (setCacheControl andThen setsurrogateControl)(req)
    if (publicReadAcl) reqWithCacheControl.acl(ObjectCannedACL.PUBLIC_READ).build() else reqWithCacheControl.build()
  }
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
            reporter.verbose(f"Check failed on attempt #$currentAttempt (Will wait for a further ${remainingTime.toFloat / 1000} seconds, retrying again after ${sleepyTime.toFloat / 1000}s)")
            Thread.sleep(sleepyTime)
            checkAttempt(currentAttempt + 1)
          } else {
            reporter.fail(s"Check failed to pass within $duration milliseconds (tried $currentAttempt times) - aborting")
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
  override def execute(resources: DeploymentResources, stopFlag: => Boolean) {
    resources.reporter.info("Hello to " + host.name + "!")
  }

  def description: String = "to " + host.name
}

case class ChangeSwitch(host: Host, protocol:String, port: Int, path: String, switchName: String, desiredState: Boolean)(implicit val keyRing: KeyRing) extends Task {
  val desiredStateName: String = if (desiredState) "ON" else "OFF"
  val switchboardUrl = s"$protocol://${host.name}:$port$path"

  // execute this task (should throw on failure)
  override def execute(resources: DeploymentResources, stopFlag: => Boolean): Unit = {
    resources.reporter.verbose(s"Changing $switchName to $desiredStateName using $switchboardUrl")

    val request = new Request.Builder()
      .url(
        HttpUrl.parse(switchboardUrl).newBuilder()
          .addQueryParameter(switchName, desiredStateName).build()
      )
      .post(new FormBody.Builder().build())
      .build()

    try {
      resources.reporter.verbose(s"Changing switch with request: $request")
      val result = ChangeSwitch.client.newCall(request).execute()
      if (result.code() != 200) {
        resources.reporter.fail(
          s"Couldn't set $switchName to $desiredState, status was ${result.code}:\n${result.body().string()}")
      }
      result.body().close()
    } catch {
      case NonFatal(t) =>
        resources.reporter.fail(s"Couldn't set $switchName to $desiredState", t)
    }
  }

  def description: String = s"$switchName to $desiredStateName using switchboard at $switchboardUrl"
}

object ChangeSwitch {
  val client = new OkHttpClient()
}

case class UpdateS3Lambda(function: LambdaFunction, s3Bucket: String, s3Key: String, region: Region)(implicit val keyRing: KeyRing) extends Task {
  def description = s"Updating $function Lambda using S3 $s3Bucket:$s3Key"

  override def execute(resources: DeploymentResources, stopFlag: => Boolean) {
    Lambda.withLambdaClient(keyRing, region, resources){ client =>
      val functionName: String = function match {
        case LambdaFunctionName(name) => name
        case LambdaFunctionTags(tags) =>
          val functionConfig = Lambda.findFunctionByTags(tags, resources.reporter, client)
          functionConfig.map(_.functionName).getOrElse{
            resources.reporter.fail(s"Failed to find any function with tags $tags")
          }
      }

      resources.reporter.verbose(s"Starting update $function Lambda")
      client.updateFunctionCode(Lambda.lambdaUpdateFunctionCodeRequest(functionName, s3Bucket, s3Key))
      resources.reporter.verbose(s"Finished update $function Lambda")
    }
  }

}
