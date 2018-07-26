package housekeeping

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{ListObjectsV2Request, ObjectTagging, SetObjectTaggingRequest, Tag}
import conf.Configuration
import controllers.Logging
import deployment.{DeployFilter, Deployments, PaginationView}
import lifecycle.Lifecycle
import org.joda.time.{DateTime, Duration, LocalTime}
import utils.{DailyScheduledAgentUpdate, ScheduledAgent}

import scala.annotation.tailrec
import scala.collection.JavaConverters._

object ArtifactHousekeeping {
  @tailrec
  private def pagedAwsRequest[T](continuationToken: Option[String] = None, acc: List[T] = Nil)(f: Option[String] => (List[T], Option[String])): List[T] = {
    val (values: List[T], nextToken: Option[String]) = f(continuationToken)
    val ts = acc ::: values
    nextToken match {
      case token @ Some(_) => pagedAwsRequest(token, ts)(f)
      case None => ts
    }
  }

  def getProjectNames(client: AmazonS3, bucket: String) = {
    pagedAwsRequest(){ token =>
      val request = new ListObjectsV2Request()
        .withDelimiter("/")
        .withBucketName(bucket)
        .withContinuationToken(token.orNull)
      val result = client.listObjectsV2(request)
      result.getCommonPrefixes.asScala.toList.map(_.stripSuffix("/")) -> Option(result.getNextContinuationToken)
    }
  }

  def getBuildIds(client: AmazonS3, bucket: String, projectName: String) = {
    val prefix = s"$projectName/"
    pagedAwsRequest(){ token =>
      val request = new ListObjectsV2Request()
        .withDelimiter("/")
        .withBucketName(bucket)
        .withPrefix(prefix)
        .withContinuationToken(token.orNull)
      val result = client.listObjectsV2(request)
      result.getCommonPrefixes.asScala.toList.map(_.stripPrefix(prefix).stripSuffix("/")) -> Option(result.getNextContinuationToken)
    }
  }
}

class ArtifactHousekeeping(deployments: Deployments) extends Logging with Lifecycle {
  private val s3Client = Configuration.artifact.aws.client
  private val artifactBucketName = Configuration.artifact.aws.bucketName

  lazy val housekeepingTime = new LocalTime(Configuration.housekeeping.tagOldArtifacts.hourOfDay, Configuration.housekeeping.tagOldArtifacts.minuteOfHour)

  var scheduledAgent:Option[ScheduledAgent[Int]] = None

  val update = DailyScheduledAgentUpdate[Int](housekeepingTime){ _ + housekeepArtifacts(new DateTime()) }

  def init() {
    scheduledAgent = Some(ScheduledAgent(0, update))
  }
  def shutdown() {
    scheduledAgent.foreach(_.shutdown())
    scheduledAgent = None
  }

  def getBuildIdsToKeep(projectName: String) = {
    val deployList = deployments.getDeploys(
      filter = Some(DeployFilter(projectName = Some(s"^$projectName$$"))),
      pagination = PaginationView(pageSize=Some(Configuration.housekeeping.tagOldArtifacts.numberToScan))
    )
    val perStageDeploys = deployList.groupBy(_.stage).values
    val deploysToKeep = perStageDeploys.flatMap(_.sortBy(-_.time.getMillis).take(Configuration.housekeeping.tagOldArtifacts.numberToKeep))
    deploysToKeep.map(_.buildId)
  }

  def tagBuilds(client: AmazonS3, bucket: String, projectName: String, buildsToTag: Set[String], now: DateTime): Int = {
    val tag = new Tag(Configuration.housekeeping.tagOldArtifacts.tagKey, Configuration.housekeeping.tagOldArtifacts.tagValue)
    val taggingObj = new ObjectTagging(List(tag).asJava)
    buildsToTag.foreach { buildId =>
      log.info(s"Tagging build ID $buildId")
      val objects = ArtifactHousekeeping.pagedAwsRequest() { token =>
        val request = new ListObjectsV2Request()
          .withDelimiter("/")
          .withBucketName(artifactBucketName)
          .withPrefix(s"$projectName/$buildId/")
          .withContinuationToken(token.orNull)
        val result = client.listObjectsV2(request)
        result.getObjectSummaries.asScala.toList -> Option(result.getNextContinuationToken)
      }

      val objectsToTag = objects.filter { obj =>
        val age = new Duration(new DateTime(obj.getLastModified), now)
        age.getStandardDays > Configuration.housekeeping.tagOldArtifacts.minimumAgeDays
      }

      objectsToTag.foreach { obj =>
        log.info(s"Tagging ${obj.getKey}")
        val request = new SetObjectTaggingRequest(bucket, obj.getKey, taggingObj)
        client.setObjectTagging(request)
      }
      Thread.sleep(500)
    }
    buildsToTag.size
  }

  def housekeepArtifacts(now: DateTime): Int = {
    if (Configuration.housekeeping.tagOldArtifacts.enabled) {
      log.info("Running housekeeping")
      val projectNames = ArtifactHousekeeping.getProjectNames(s3Client, artifactBucketName)
      val taggedBuilds = projectNames.map { name =>
        log.info(s"Housekeeping project '$name'")
        val buildIdsForProject = ArtifactHousekeeping.getBuildIds(s3Client, artifactBucketName, name).toSet
        val buildIdsToKeep = getBuildIdsToKeep(name).toSet
        val missingBuilds = buildIdsToKeep -- buildIdsForProject
        if (missingBuilds.nonEmpty) {
          log.error("Some builds we wanted to keep were not found, possible something is awry. Skipping tagging.")
          0
        } else {
          val buildsToTag = buildIdsForProject -- buildIdsToKeep
          tagBuilds(s3Client, artifactBucketName, name, buildsToTag, now)
        }
      }
      val numberOfTaggedBuilds = taggedBuilds.sum
      log.info(s"Tagged $numberOfTaggedBuilds builds for deletion")
      numberOfTaggedBuilds
    } else {
      log.info("Artifact housekeeping not enabled - skipping")
      0
    }
  }
}
