package housekeeping

import _root_.lifecycle.Lifecycle
import software.amazon.awssdk.services.s3.model._
import conf.Config
import controllers.Logging
import deployment.{DeployFilter, Deployments, PaginationView}
import magenta.RunState
import org.joda.time.{DateTime, Duration, LocalTime}
import software.amazon.awssdk.services.s3.S3Client
import utils.{DailyScheduledAgentUpdate, ScheduledAgent}

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

object ArtifactHousekeeping {
  @tailrec
  private def pagedAwsRequest[T](
      continuationToken: Option[String] = None,
      acc: List[T] = Nil
  )(f: Option[String] => (List[T], Option[String])): List[T] = {
    val (values: List[T], nextToken: Option[String]) = f(continuationToken)
    val ts = acc ::: values
    nextToken match {
      case token @ Some(_) => pagedAwsRequest(token, ts)(f)
      case None            => ts
    }
  }

  def getProjectNames(client: S3Client, bucket: String): List[String] = {
    pagedAwsRequest() { token =>
      val request = ListObjectsV2Request
        .builder()
        .delimiter("/")
        .bucket(bucket)
        .continuationToken(token.orNull)
        .build()
      val result = client.listObjectsV2(request)
      result.commonPrefixes.asScala.toList.map(
        _.prefix.stripSuffix("/")
      ) -> Option(result.nextContinuationToken())
    }
  }

  def getBuildIds(
      client: S3Client,
      bucket: String,
      projectName: String
  ): List[String] = {
    val prefix = s"$projectName/"
    pagedAwsRequest() { token =>
      val request = ListObjectsV2Request
        .builder()
        .delimiter("/")
        .bucket(bucket)
        .prefix(prefix)
        .continuationToken(token.orNull)
        .build()
      val result = client.listObjectsV2(request)
      result.commonPrefixes.asScala.toList.map(
        _.prefix.stripPrefix(prefix).stripSuffix("/")
      ) -> Option(result.nextContinuationToken)
    }
  }

  def getBuildIdsToKeep(
      deployments: Deployments,
      projectName: String,
      numberToScan: Int,
      numberToKeep: Int
  ): Either[Throwable, List[String]] = {
    for {
      deployList <- deployments.getDeploys(
        filter = Some(
          DeployFilter(
            projectName = Some(s"$projectName"),
            isExactMatchProjectName = Some(true),
            status = Some(RunState.Completed)
          )
        ),
        pagination = PaginationView(pageSize = Some(numberToScan))
      )
    } yield {
      val perStageDeploys = deployList.groupBy(_.stage).values.toList
      val deploysToKeep =
        perStageDeploys.flatMap(_.sortBy(-_.time.getMillis).take(numberToKeep))
      deploysToKeep.map(_.buildId)
    }
  }

  def getObjectsToTag(
      client: S3Client,
      artifactBucketName: String,
      projectName: String,
      buildId: String,
      now: DateTime,
      minimumAgeDays: Int
  ): List[S3Object] = {
    val objects = pagedAwsRequest() { token =>
      val request = ListObjectsV2Request
        .builder()
        .bucket(artifactBucketName)
        .prefix(s"$projectName/$buildId/")
        .continuationToken(token.orNull)
        .build()
      val result = client.listObjectsV2(request)
      result.contents.asScala.toList -> Option(result.nextContinuationToken)
    }

    objects.filter { obj =>
      val age = new Duration(new DateTime(obj.lastModified.toEpochMilli), now)
      age.getStandardDays > minimumAgeDays
    }
  }
}

class ArtifactHousekeeping(config: Config, deployments: Deployments)
    extends Logging
    with Lifecycle {
  private val s3Client = config.artifact.aws.client
  private val artifactBucketName = config.artifact.aws.bucketName

  lazy val housekeepingTime = new LocalTime(
    config.housekeeping.tagOldArtifacts.hourOfDay,
    config.housekeeping.tagOldArtifacts.minuteOfHour
  )

  var scheduledAgent: Option[ScheduledAgent[Int]] = None

  val update = DailyScheduledAgentUpdate[Int](housekeepingTime) {
    _ + housekeepArtifacts(new DateTime())
  }

  def init(): Unit = {
    scheduledAgent = Some(ScheduledAgent(0, update))
  }
  def shutdown(): Unit = {
    scheduledAgent.foreach(_.shutdown())
    scheduledAgent = None
  }

  def tagBuilds(
      client: S3Client,
      bucket: String,
      projectName: String,
      buildsToTag: Set[String],
      now: DateTime
  ): Int = {
    val tag = Tag
      .builder()
      .key(config.housekeeping.tagOldArtifacts.tagKey)
      .value(config.housekeeping.tagOldArtifacts.tagValue)
      .build()
    val taggingObj = Tagging.builder.tagSet(List(tag).asJava).build()

    buildsToTag.foreach { buildId =>
      log.info(s"Tagging build ID $buildId")
      val objectsToTag = ArtifactHousekeeping.getObjectsToTag(
        client,
        bucket,
        projectName,
        buildId,
        now,
        config.housekeeping.tagOldArtifacts.minimumAgeDays
      )

      objectsToTag.foreach { obj =>
        log.info(s"Tagging ${obj.key}")
        val request = PutObjectTaggingRequest
          .builder()
          .bucket(bucket)
          .key(obj.key)
          .tagging(taggingObj)
          .build()
        client.putObjectTagging(request)
      }
      Thread.sleep(500)
    }
    buildsToTag.size
  }

  def housekeepArtifacts(now: DateTime): Int = {
    if (config.housekeeping.tagOldArtifacts.enabled) {
      log.info("Running housekeeping")
      val projectNames =
        ArtifactHousekeeping.getProjectNames(s3Client, artifactBucketName)
      val taggedBuilds = projectNames.map { name =>
        log.info(s"Housekeeping project '$name'")
        val buildIdsForProject = ArtifactHousekeeping
          .getBuildIds(s3Client, artifactBucketName, name)
          .toSet
        ArtifactHousekeeping.getBuildIdsToKeep(
          deployments,
          name,
          config.housekeeping.tagOldArtifacts.numberToScan,
          config.housekeeping.tagOldArtifacts.numberToKeep
        ) match {
          case Left(_) =>
            log.warn(
              s"Failed to get list of builds to keep for project $name - not housekeeping this project"
            )
            0
          case Right(buildIdsToKeep) if buildIdsToKeep.nonEmpty =>
            val buildIdsToKeepSet = buildIdsToKeep.toSet
            log.info(
              s"Keeping ${buildIdsToKeepSet.size} builds of $name (${buildIdsToKeepSet.toList.sorted})"
            )
            val missingBuilds = buildIdsToKeepSet -- buildIdsForProject
            if (missingBuilds.nonEmpty) {
              log.error(
                "Some builds we wanted to keep were not found, possible something is awry. Skipping tagging."
              )
              0
            } else {
              val buildsToTag = buildIdsForProject -- buildIdsToKeepSet
              tagBuilds(s3Client, artifactBucketName, name, buildsToTag, now)
            }
          case Right(_) =>
            log.error(
              s"List of builds to keep for project $name was empty, possible something is awry. Skipping tagging."
            )
            0
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
