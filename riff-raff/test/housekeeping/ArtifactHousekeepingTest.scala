package housekeeping

import java.util
import java.util.UUID

import com.amazonaws.services.s3.model.{ListObjectsV2Request, ListObjectsV2Result, S3ObjectSummary, SetObjectTaggingRequest}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import deployment._
import magenta.{Build, DeployParameters, Deployer, RunState, Stage}
import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._

class ArtifactHousekeepingTest extends FlatSpec with Matchers with MockitoSugar {

  case class ObjectSummary(key: String, bucketName: String, lastModified: DateTime)

  val oldDate = new DateTime(2018, 5, 12, 0, 0, 0, DateTimeZone.UTC)
  val newDate = new DateTime(2018, 6, 20, 0, 0, 0, DateTimeZone.UTC)

  val housekeepingDate = new DateTime(2018, 6, 28, 0, 0, 0, DateTimeZone.UTC)

  private def mockListObjectsV2Result(objectSummaries: List[ObjectSummary]): ListObjectsV2Result = {
    val summaries: List[S3ObjectSummary] = objectSummaries.map { obj =>
      val summary = new S3ObjectSummary()
      summary.setKey(obj.key)
      summary.setBucketName(obj.bucketName)
      summary.setLastModified(obj.lastModified.toDate)
      summary
    }

    new ListObjectsV2Result() {
      override def getObjectSummaries: util.List[S3ObjectSummary] = {
        summaries.asJava
      }
    }
  }

  private def fixtureRecord(date: DateTime, stageName: String, buildNumber: String): Record = DeployRecord(
    date,
    UUID.fromString("7fa2ee0a-8d90-4f7e-a38b-185f36fbc5aa"),
    DeployParameters(Deployer("anon"), Build("testProject", buildNumber), Stage(stageName)),
    recordState = Some(RunState.Completed)
  )

  val oldDeploys: List[Record] = List.tabulate(6)(
    n => fixtureRecord(oldDate.plusHours(n), "PROD", s"2$n")
  ) ++ List.tabulate(6)(
    n => fixtureRecord(oldDate.plusHours(n), "CODE", s"1$n")
  )
  val newDeploys: List[Record] = List.tabulate(6)(
    n => fixtureRecord(newDate.plusHours(n), "PROD", s"1$n")
  ) ++ List.tabulate(6)(
    n => fixtureRecord(newDate.plusHours(n), "CODE", s"2$n")
  )


  "getProjectNames" should "make a single listObjectsV2 request and return the project names" in {
    val artifactClientMock: AmazonS3 = mock[AmazonS3Client]
    val listObjectsResult: ListObjectsV2Result = new ListObjectsV2Result()
    listObjectsResult.setCommonPrefixes(List("project-name-1/", "project-name-2/", "project-name-3/").asJava)

    when(artifactClientMock.listObjectsV2(any[ListObjectsV2Request])) thenReturn listObjectsResult

    val result = ArtifactHousekeeping.getProjectNames(artifactClientMock, "bucket-name")
    verify(artifactClientMock, times(1)).listObjectsV2(any[ListObjectsV2Request])
    result shouldEqual List("project-name-1", "project-name-2", "project-name-3")
  }

  "getBuildIds" should "make a single listObjectsV2 request and return the build IDs" in {
    val artifactClientMock: AmazonS3 = mock[AmazonS3Client]
    val listObjectsResult: ListObjectsV2Result = new ListObjectsV2Result()
    listObjectsResult.setCommonPrefixes(List("project-name/10/", "project-name/11/", "project-name/12/").asJava)
    when(artifactClientMock.listObjectsV2(any[ListObjectsV2Request])) thenReturn listObjectsResult

    val result = ArtifactHousekeeping.getBuildIds(artifactClientMock, "bucket-name", "project-name")
    verify(artifactClientMock, times(1)).listObjectsV2(any[ListObjectsV2Request])
    result shouldEqual List("10", "11", "12")
  }

  "getBuildIdsToKeep" should "find the most recent builds for each stage" in {
    val deploymentsMock = mock[Deployments]
    when (deploymentsMock.getDeploys(any[Option[DeployFilter]], any[PaginationView], any[Boolean])) thenReturn
      Right(oldDeploys ++ newDeploys)

    val result = ArtifactHousekeeping.getBuildIdsToKeep(deploymentsMock, "testProject")
    val recentCodeDeploys = List("25", "24", "23", "22", "21")
    val recentProdDeploys = List("15", "14", "13", "12", "11")
    result shouldEqual Right(recentProdDeploys ++ recentCodeDeploys)

  }

  it should "find the older build that has been recently deployed" in {
    val deploymentsMock = mock[Deployments]
    val oldBuildToProd = List(fixtureRecord(newDate.plusHours(23), "PROD", "10"))
    when (deploymentsMock.getDeploys(any[Option[DeployFilter]], any[PaginationView], any[Boolean])) thenReturn
      Right(oldDeploys ++ newDeploys ++ oldBuildToProd)

    val result = ArtifactHousekeeping.getBuildIdsToKeep(deploymentsMock, "testProject")
    val recentCodeDeploys = List("25", "24", "23", "22", "21")
    val recentProdDeploys = List("10", "15", "14", "13", "12")
    result shouldEqual Right(recentProdDeploys ++ recentCodeDeploys)
  }

  it should "find all the deploys if there are fewer than the configured limit" in {
    val deploymentsMock = mock[Deployments]
    val deploys = List(
      fixtureRecord(oldDate, "PROD", "2"),
      fixtureRecord(oldDate, "CODE", "3"),
      fixtureRecord(oldDate, "CODE", "4"),
      fixtureRecord(oldDate, "CODE", "5")
    )
    when (deploymentsMock.getDeploys(any[Option[DeployFilter]], any[PaginationView], any[Boolean])) thenReturn
      Right(deploys)

    val result = ArtifactHousekeeping.getBuildIdsToKeep(deploymentsMock, "testProject")
    result shouldEqual Right(List("2", "3", "4", "5"))
  }

  "getObjectsToTag" should "only return objects that are older than the configured date" in {
    val artifactClientMock: AmazonS3 = mock[AmazonS3Client]

    when(artifactClientMock.listObjectsV2(any[ListObjectsV2Request])) thenReturn mockListObjectsV2Result(
      List.tabulate(3)(n => ObjectSummary(s"object-x$n", "project-name", oldDate.plusDays(n))) ++
        List.tabulate(3)(n => ObjectSummary(s"object-z$n", "project-name", newDate.plusDays(n)))
    )

    val result = ArtifactHousekeeping.getObjectsToTag(artifactClientMock, "bucket-name", "project-name", "12", housekeepingDate)
    verify(artifactClientMock, times(1)).listObjectsV2(any[ListObjectsV2Request])
    result.map(_.getKey) shouldEqual List("object-x0", "object-x1", "object-x2")
  }

  "tagBuilds" should "not call setObjectTagging, and return zero when there are no builds to tag" in {
    val artifactClientMock: AmazonS3 = mock[AmazonS3Client]
    val deploymentsMock = mock[Deployments]
    val artifactHousekeeping = new ArtifactHousekeeping(deploymentsMock)

    when(artifactClientMock.listObjectsV2(any[ListObjectsV2Request])) thenReturn mockListObjectsV2Result(List())

    val result = artifactHousekeeping.tagBuilds(artifactClientMock, "bucket-name", "project-name", Set(), housekeepingDate)
    verify(artifactClientMock, times(0)).setObjectTagging(any[SetObjectTaggingRequest])
    result shouldEqual 0
  }

  it should "call setObjectTagging for each object, then return the number of builds that have been tagged" in {
    val artifactClientMock: AmazonS3 = mock[AmazonS3Client]
    val deploymentsMock = mock[Deployments]
    val artifactHousekeeping = new ArtifactHousekeeping(deploymentsMock)

    when(artifactClientMock.listObjectsV2(any[ListObjectsV2Request])) thenReturn mockListObjectsV2Result(
      List(
        ObjectSummary(s"object-1", "project-name", oldDate),
        ObjectSummary(s"object-2", "project-name", oldDate)
      ))

    val result = artifactHousekeeping.tagBuilds(artifactClientMock, "bucket-name", "project-name", Set("11", "12"), housekeepingDate)
    verify(artifactClientMock, times(4)).setObjectTagging(any[SetObjectTaggingRequest])
    result shouldEqual 2
  }

  "ArtifactHousekeeping" should "do nothing and return zero if not enabled" in {
    val deploymentsMock = mock[Deployments]
    val artifactHousekeeping = new ArtifactHousekeeping(deploymentsMock)
    artifactHousekeeping.housekeepArtifacts(housekeepingDate) shouldEqual 0
  }
}
