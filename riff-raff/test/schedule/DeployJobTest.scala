package schedule

import java.util.UUID

import deployment.{DeployRecord, Error}
import magenta.{Build, Deployer, DeployParameters, RunState, Stage}
import org.joda.time.DateTime
import org.scalatest.{EitherValues, FlatSpec, Matchers}

class DeployJobTest extends FlatSpec with Matchers with EitherValues {
  val uuid = UUID.fromString("7fa2ee0a-8d90-4f7e-a38b-185f36fbc5aa")
  "createDeployParameters" should "return params if valid" in {
    val record = new DeployRecord(
      new DateTime(),
      uuid,
      DeployParameters(Deployer("Bob"), Build("testProject", "1"), Stage("TEST")),
      recordState = Some(RunState.Completed)
    )
    DeployJob.createDeployParameters(record, true) shouldBe
      Right(DeployParameters(Deployer("Scheduled Deployment"), Build("testProject", "1"), Stage("TEST")))
  }

  it should "produce an error if the last deploy didn't complete" in {
    val record = new DeployRecord(
      new DateTime(),
      uuid,
      DeployParameters(Deployer("Bob"), Build("testProject", "1"), Stage("TEST")),
      recordState = Some(RunState.Failed)
    )
    DeployJob.createDeployParameters(record, true) shouldBe
      Left(Error("Skipping scheduled deploy as deploy record 7fa2ee0a-8d90-4f7e-a38b-185f36fbc5aa has status Failed"))
  }

  it should "produce an error if scheduled deploys are disabled" in {
    val record = new DeployRecord(
      new DateTime(),
      uuid,
      DeployParameters(Deployer("Bob"), Build("testProject", "1"), Stage("TEST")),
      recordState = Some(RunState.Completed)
    )
    DeployJob.createDeployParameters(record, false) shouldBe
      Left(Error("Scheduled deployments disabled. Would have deployed DeployParameters(Deployer(Scheduled Deployment),Build(testProject,1),Stage(TEST),All)"))
  }
}
