package magenta.tasks.gcp

import java.math.BigInteger
import java.net.URLConnection
import java.util.Arrays
import com.google.api.client.http.InputStreamContent
import com.google.api.services.storage.Storage
import com.google.api.services.storage.model.{ObjectAccessControl, StorageObject}
import magenta.{DeploymentResources, KeyRing, Loggable, Stack, Stage}
import magenta.artifact.{S3Location, S3Object, S3Path}
import magenta.deployment_type.GcsTargetBucket
import magenta.deployment_type.param_reads.PatternValue
import magenta.tasks.Task
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{DeleteObjectRequest, GetObjectRequest}

import scala.collection.parallel.CollectionConverters._

import scala.collection.JavaConverters._

case class GCSUpload(
  gcsTargetBucket: GcsTargetBucket,
  paths: Seq[(S3Location, String)],
  cacheControlPatterns: List[PatternValue] = Nil,
  publicReadAcl: Boolean = false
)(implicit val keyRing: KeyRing, artifactClient: S3Client,
  withClientFactory: (KeyRing, DeploymentResources) => (Storage => Unit) => Unit = GCS.withGCSClient[Unit]) extends Task with Loggable {

  private val PublicAcl = Arrays.asList(new ObjectAccessControl().setEntity("allUsers").setRole("READER"))

  lazy val objectMappings: Seq[(S3Object, GCSPath)] = paths flatMap {
    case (file, targetKey) => resolveMappings(file, targetKey, gcsTargetBucket.name)
  }

  lazy val totalSize: Long = objectMappings.map{ case (source, _) => source.size }.sum

  lazy val transfers: Seq[StorageObjectTransfer] = objectMappings.map { case (source, target) =>
    val storageObject = new StorageObject().setBucket(gcsTargetBucket.name)
                                           .setName(target.key)
                                           .setSize(BigInteger.valueOf(source.size))
                                           .setContentType(URLConnection.guessContentTypeFromName(target.key))

    cacheControlLookup(target.key) match {
      case Some(cacheControl) => storageObject.setCacheControl(cacheControl)
      case None               => ()
    }

    if (publicReadAcl) storageObject.setAcl(PublicAcl)

    StorageObjectTransfer(source, storageObject)
  }

  def fileString(quantity: Int) = s"$quantity file${if (quantity != 1) "s" else ""}"

  // end-user friendly description of this task
  def description: String = s"Upload ${fileString(objectMappings.size)} to GCS bucket $gcsTargetBucket using file mapping $paths"

  // execute this task (should throw on failure)
  override def execute(resources: DeploymentResources, stopFlag: => Boolean): Unit = {
    if (totalSize == 0) {
      val locationDescription = paths.map {
        case (path: S3Path, _) => path.show()
        case (location, _) => location.toString
      }.mkString("\n")
      resources.reporter.fail(s"No files found to upload in $locationDescription")
    }

    val withClient = withClientFactory(keyRing, resources)
    withClient { client =>

      val currentlyDeployedObjectsToDelete = getCurrentObjectsForDeletion(client)

      resources.reporter.verbose(s"Starting transfer of ${fileString(objectMappings.size)} ($totalSize bytes)")
      transfers.zipWithIndex.par.foreach { case (transfer, index) =>
        logger.debug(s"Transferring $transfer")
        index match {
          case x if x < 10 => resources.reporter.verbose(s"Transferring $transfer")
          case 10 => resources.reporter.verbose(s"Not logging details for the remaining ${fileString(objectMappings.size - 10)}")
          case _ =>
        }
        GCP.api.retryWhen500orGoogleError(resources.reporter, s"GCS Upload $transfer") {
          val copyObjectRequest = GetObjectRequest.builder()
                                                  .bucket(transfer.source.bucket)
                                                  .key(transfer.source.key)
                                                  .build()
          val inputStream = resources.artifactClient.getObjectAsBytes(copyObjectRequest).asInputStream()
          val contentType = Option(transfer.target.getContentType).getOrElse(URLConnection.guessContentTypeFromStream(inputStream))
          val result      = client.objects().insert(gcsTargetBucket.name, transfer.target, new InputStreamContent(contentType, inputStream)).execute()
          logger.debug(s"Put object ${result.getName}: MD5: ${result.getMd5Hash} Metadata: ${result.getMetadata}")
          result
        }
      }
      currentlyDeployedObjectsToDelete.par.foreach { case storageObjectToDelete =>
        resources.reporter.verbose(s"Deleting obsolete file from GCP: gcs://${gcsTargetBucket.name}/${storageObjectToDelete.getName}")
        val errorMessage = s"Could not 0emove obselete object ${storageObjectToDelete.getName}"
        GCP.api.retryWhen500orGoogleError(resources.reporter, errorMessage) {
          client.objects().delete(gcsTargetBucket.name, storageObjectToDelete.getName).execute
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

  private def resolveMappings(path: S3Location, targetKey: String, targetBucket: String): Seq[(S3Object, GCSPath)] = {
    path.listAll()(artifactClient).map { obj =>
      obj -> GCSPath(targetBucket, subDirectoryPrefix(targetKey, obj.relativeTo(path)))
    }
  }

  private def cacheControlLookup(fileName:String) = cacheControlPatterns.find(_.regex.findFirstMatchIn(fileName).isDefined).map(_.value)

  private def getCurrentObjectsForDeletion(storage: Storage) : List[StorageObject] = {

    val objectsInThisDeploy = transfers.map(_.target).toList

    def getAllObjectsForDirectory(query: Storage#Objects#List,  foundSoFar: List[StorageObject] = List.empty): List[StorageObject] = {

      val listResults = query.execute
      val currentPageItems = Option(listResults.getItems).map(resultsSet => resultsSet.asScala).getOrElse(List.empty)
      val allItemsFoundSoFar = foundSoFar ++ currentPageItems

      Option(listResults.getNextPageToken) match {
        case Some(token) => getAllObjectsForDirectory(query.setPageToken(token), allItemsFoundSoFar)
        case None => allItemsFoundSoFar
      }
    }

    def allObjectsInMatchingDirectories(directoriesToPurge: List[String], itemsSoFar: List[StorageObject] = List.empty): List[StorageObject] =
        directoriesToPurge match {
          case Nil => itemsSoFar
          case head :: tail =>
            val gcsQuery = storage.objects()
             .list(gcsTargetBucket.name)
             .setPrefix(head)
            val allItemsForThisDirectory = getAllObjectsForDirectory(gcsQuery, itemsSoFar)
            allObjectsInMatchingDirectories(tail, itemsSoFar ::: allItemsForThisDirectory )
        }

    def tidyFileType(configuredFileType: String) : String = {
      if (configuredFileType.startsWith("."))
        configuredFileType
      else
      s".${configuredFileType}"
    }

    def filterListByFileTypes(allDeployedObjects: List[StorageObject], filetypesToPurge: List[String], matchingObjects: List[StorageObject] = List.empty ): List[StorageObject] = {
      filetypesToPurge match {
        case Nil => matchingObjects
        case head :: tail =>
          val safeFileExtension = tidyFileType(head)
          val objectsMatchingThisFiletype = allDeployedObjects.filter(ob => ob.getName.endsWith(safeFileExtension))
          filterListByFileTypes(allDeployedObjects, tail, matchingObjects ::: objectsMatchingThisFiletype)
      }
    }

    def findCurrentObjectsNotInThisTransfer(objectsToCheckInCurrentDeploy: List[StorageObject], objectsPreviouslyDeployed: List[StorageObject], objectsToDelete: List[StorageObject] = List.empty): List[StorageObject] = {
      objectsToCheckInCurrentDeploy match {
        case Nil => objectsToDelete
        case head :: tail =>
          objectsPreviouslyDeployed.find( so => so.getName == head.getName) match {
            case Some(_) =>
              //Was previously deployed and is being re-deployed, keep it
              findCurrentObjectsNotInThisTransfer(tail, objectsPreviouslyDeployed, objectsToDelete)
            case None =>
              //Was previously deployed but not in this deploy. Delete it
              findCurrentObjectsNotInThisTransfer(tail, objectsPreviouslyDeployed,  head :: objectsToDelete)
          }
      }
    }

    val allItemsForConfiguredDirectories = gcsTargetBucket.directoriesToPurge match {
      //Nothing to delete = This isn't datatech's composer usecase, but hey ..
      case Nil => List.empty
      case directoriesToPurge => allObjectsInMatchingDirectories(directoriesToPurge)
    }

    val allItemsFilteredByType = filterListByFileTypes(allItemsForConfiguredDirectories, gcsTargetBucket.fileTypesToPurge)
    findCurrentObjectsNotInThisTransfer(allItemsFilteredByType, objectsInThisDeploy)
  }
}

case class StorageObjectTransfer(source: S3Object, target: StorageObject) {
  override def toString: String =
    s"s3://${source.bucket}/${source.key} to gcs://${target.getBucket}/${target.getName} with " +
      s"CacheControl:${target.getCacheControl} ContentType:${target.getContentType} PublicRead:${target.getAcl}"
}

object GCSUpload {
  def prefixGenerator(stack:Option[Stack] = None, stage:Option[Stage] = None, packageName:Option[String] = None): String = {
    (stack.map(_.name) :: stage.map(_.name) :: packageName :: Nil).flatten.mkString("/")
  }
  def prefixGenerator(stack: Stack, stage: Stage, packageName: String): String =
    prefixGenerator(Some(stack), Some(stage), Some(packageName))
}