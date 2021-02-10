package notification

import ci.ContinuousDeployment
import com.gu.anghammarad.models.{Action, Stack, Target}
import deployment.{Fixtures, NoDeploysFoundForStage, SkippedDueToPreviousFailure, SkippedDueToPreviousWaitingDeploy}
import magenta.{Build, DeployParameters, Stage}
import org.scalatest.{FunSuite, Matchers}

import java.util.UUID

class FailureNotificationContentsTest extends FunSuite with Matchers {

  test("should produce sensible notification contents for a failed Continuous Deployment") {
    val failureNotificationContents = new FailureNotificationContents("http://localhost:9000")
    val uuid = UUID.randomUUID()
    val parameters = DeployParameters(ContinuousDeployment.deployer, Build("project", "123"), Stage("PROD"))
    val expected = NotificationContents(
      subject = "Continuous Deployment failed",
      message = "Continuous Deployment for project (build 123) to stage PROD failed.",
      actions = List(Action("View failed deploy", s"http://localhost:9000/deployment/view/$uuid?verbose=true"))
    )
    val result = failureNotificationContents.deployFailedNotificationContents(uuid, parameters)
    result shouldBe expected
  }

  test("should produce sensible notification contents for a Scheduled Deployment which fails to start due to previous failed deploy") {
    val failureNotificationContents = new FailureNotificationContents("http://localhost:9000")
    val record = Fixtures.createRecord(
      projectName = "testProject",
      buildId = "123",
      stage = "PROD"
    )
    def fakeGetTargets(uuid: UUID, parameters: DeployParameters): List[Target] = List(Stack("another-team-stack"))
    val error = SkippedDueToPreviousFailure(record)
    val expectedContents = NotificationContents(
      subject = "Scheduled Deployment failed to start",
      message = "Scheduled Deployment for testProject to PROD didn't start because the most recent deploy failed.",
      actions = List(
        Action("View failed deploy", s"http://localhost:9000/deployment/view/${record.uuid}?verbose=true"),
        Action("Redeploy manually", s"http://localhost:9000/deployment/deployAgain/${record.uuid}")
      ),
    )
    val result = failureNotificationContents.deployUnstartedNotificationContents(error, fakeGetTargets, List(Stack("deploy")))
    result.notificationContents shouldBe expectedContents
  }

  test("should produce sensible notification contents for a Scheduled Deployment which fails to start due to previous waiting deploy") {
    val failureNotificationContents = new FailureNotificationContents("http://localhost:9000")
    val record = Fixtures.createRecord(
      projectName = "testProject",
      buildId = "123",
      stage = "PROD"
    )
    def fakeGetTargets(uuid: UUID, parameters: DeployParameters): List[Target] = List(Stack("another-team-stack"))
    val error = SkippedDueToPreviousWaitingDeploy(record)
    val expectedContents = NotificationContents(
      subject = "Scheduled Deployment failed to start",
      message = "Scheduled Deployment for testProject to PROD failed to start as a previous deploy was still waiting to be deployed.",
      actions = List(Action("View waiting deploy", s"http://localhost:9000/deployment/view/${record.uuid}?verbose=true")),
    )
    val result = failureNotificationContents.deployUnstartedNotificationContents(error, fakeGetTargets, List(Stack("deploy")))
    result.notificationContents shouldBe expectedContents
  }

  test("should produce sensible notification contents for a Scheduled Deployment which fails to start due to a configuration issue") {
    val failureNotificationContents = new FailureNotificationContents("http://localhost:9000")
    def fakeGetTargets(uuid: UUID, parameters: DeployParameters): List[Target] = List(Stack("another-team-stack"))
    val error = NoDeploysFoundForStage("testProject", "PROD")
    val expectedContents = NotificationContents(
      subject = "Scheduled Deployment failed to start",
      message = "A scheduled deploy didn't start because Riff-Raff has never deployed testProject to PROD before. Please inform the owner of this schedule as it's likely that they have made a configuration error.",
      actions = List(Action("View Scheduled Deployment configuration", s"http://localhost:9000/deployment/schedule"))
    )
    val result = failureNotificationContents.deployUnstartedNotificationContents(error, fakeGetTargets, List(Stack("deploy")))
    result.notificationContents shouldBe expectedContents
  }

  test("should use team-based targets for a Scheduled Deployment which fails to start due to previous failed deploy") {
    val failureNotificationContents = new FailureNotificationContents("http://localhost:9000")
    val record = Fixtures.createRecord()
    def fakeGetTargets(uuid: UUID, parameters: DeployParameters): List[Target] = List(Stack("another-team-stack"))
    val error = SkippedDueToPreviousFailure(record)
    val expectedTargets = List(Stack("another-team-stack"))
    val result = failureNotificationContents.deployUnstartedNotificationContents(error, fakeGetTargets, List(Stack("deploy")))
    result.targets shouldBe expectedTargets
  }

  test("should use Riff-Raff maintainer targets for a Scheduled Deployment which fails to start due to previous waiting deploy") {
    val failureNotificationContents = new FailureNotificationContents("http://localhost:9000")
    val record = Fixtures.createRecord()
    def fakeGetTargets(uuid: UUID, parameters: DeployParameters): List[Target] = List(Stack("another-team-stack"))
    val error = SkippedDueToPreviousWaitingDeploy(record)
    val expectedTargets = List(Stack("deploy"))
    val result = failureNotificationContents.deployUnstartedNotificationContents(error, fakeGetTargets, List(Stack("deploy")))
    result.targets shouldBe expectedTargets
  }

  test("should use Riff-Raff maintainer targets for a Scheduled Deployment which fails to start due to a configuration issue") {
    val failureNotificationContents = new FailureNotificationContents("http://localhost:9000")
    val record = Fixtures.createRecord()
    def fakeGetTargets(uuid: UUID, parameters: DeployParameters): List[Target] = List(Stack("another-team-stack"))
    val error = NoDeploysFoundForStage("project", "PROD")
    val expectedTargets = List(Stack("deploy"))
    val result = failureNotificationContents.deployUnstartedNotificationContents(error, fakeGetTargets, List(Stack("deploy")))
    result.targets shouldBe expectedTargets
  }

}
