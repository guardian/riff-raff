package schedule

import java.util.UUID

import deployment.{DeployRecord, Error}
import magenta.{Build, Deployer, DeployParameters, RunState, Stage}
import org.joda.time.DateTime
import org.scalatest.{EitherValues, FlatSpec, Matchers}

class DeployJobTest extends FlatSpec with Matchers with EitherValues {
  private val uuid = UUID.fromString("7fa2ee0a-8d90-4f7e-a38b-185f36fbc5aa")
  private val bobsProject = DeployParameters(Deployer("Bob"), Build("testProject", "1"), Stage("TEST"))
  private val bobsSuccessfulDeploy = DeployRecord(new DateTime(), uuid, bobsProject, recordState = Some(RunState.Completed))

  "createDeployParameters" should "return params if valid" in {
    runAgainst(bobsSuccessfulDeploy) shouldBe
      Right(DeployParameters(Deployer("Scheduled Deployment"), Build("testProject", "1"), Stage("TEST")))
  }

  it should "produce an error if the last deploy didn't complete" in {
    val bobsFailedDeploy = bobsSuccessfulDeploy.copy(recordState = Some(RunState.Failed))

    runAgainst(bobsFailedDeploy) shouldBe
      Left(Error("Skipping scheduled deploy as deploy record 7fa2ee0a-8d90-4f7e-a38b-185f36fbc5aa has status Failed"))
  }

  it should "produce an error if scheduled deploys are disabled" in {
    runAgainst(bobsSuccessfulDeploy, enabled = false) shouldBe
      Left(Error("Scheduled deployments disabled. Would have deployed DeployParameters(Deployer(Scheduled Deployment),Build(testProject,1),Stage(TEST),RecipeName(default),List(),List(),All)"))
  }

  it should "produce an error if still within the cool-down days" in {
    val now = new DateTime()
    val lastDeploy = bobsSuccessfulDeploy.copy(time = now.minusDays(4))

    runAgainst(lastDeploy, cooldownDays = Some(14)).isLeft should be(true)
  }

  it should "return params if outside the cool-down days" in {
    val now = new DateTime()
    val lastDeploy = bobsSuccessfulDeploy.copy(time = now.minusDays(15))

    runAgainst(lastDeploy, cooldownDays = Some(14)).isRight should be(true)
  }

  private def runAgainst(lastDeploy: DeployRecord, enabled: Boolean = true, cooldownDays: Option[Int] = None) = {
    DeployJob.createDeployParameters(lastDeploy, enabled, now = new DateTime(), cooldownDays)
  }
}
