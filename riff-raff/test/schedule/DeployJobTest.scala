package schedule

import java.util.UUID
import deployment.{
  DeployRecord,
  Error,
  SkippedDueToPreviousFailure,
  SkippedDueToPreviousPartialDeploy
}
import magenta.Strategy.MostlyHarmless
import magenta.input.{DeploymentKey, DeploymentKeysSelector}
import magenta.{Build, DeployParameters, Deployer, RunState, Stage}
import org.joda.time.DateTime
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DeployJobTest extends AnyFlatSpec with Matchers with EitherValues {
  private val uuid = UUID.fromString("7fa2ee0a-8d90-4f7e-a38b-185f36fbc5aa")

  private val deployParameters = DeployParameters(
    Deployer("Bob"),
    Build("testProject", "1"),
    Stage("TEST"),
    updateStrategy = MostlyHarmless
  )

  "createDeployParameters" should "return params if valid" in {
    val record = DeployRecord(
      new DateTime(),
      uuid,
      deployParameters,
      recordState = Some(RunState.Completed)
    )
    DeployJob.createDeployParameters(
      record,
      scheduledDeploysEnabled = true
    ) shouldBe
      Right(
        DeployParameters(
          Deployer("Scheduled Deployment"),
          deployParameters.build,
          deployParameters.stage,
          updateStrategy = MostlyHarmless
        )
      )
  }

  it should "produce an error if the last deploy didn't complete" in {
    val record = DeployRecord(
      new DateTime(),
      uuid,
      deployParameters,
      recordState = Some(RunState.Failed)
    )
    DeployJob.createDeployParameters(
      record,
      scheduledDeploysEnabled = true
    ) shouldBe Left(
      SkippedDueToPreviousFailure(record)
    )
  }

  it should "produce an error if the last deploy was partial" in {
    val record = DeployRecord(
      new DateTime(),
      uuid,
      deployParameters.copy(
        selector = DeploymentKeysSelector(
          List(DeploymentKey("name", "action", "stack", "region"))
        )
      ),
      recordState = Some(RunState.Completed)
    )
    DeployJob.createDeployParameters(
      record,
      scheduledDeploysEnabled = true
    ) shouldBe Left(
      SkippedDueToPreviousPartialDeploy(record)
    )
  }

  it should "produce an error if scheduled deploys are disabled" in {
    val record = DeployRecord(
      new DateTime(),
      uuid,
      deployParameters,
      recordState = Some(RunState.Completed)
    )
    DeployJob.createDeployParameters(
      record,
      scheduledDeploysEnabled = false
    ) shouldBe
      Left(
        Error(
          "Scheduled deployments disabled. Would have deployed DeployParameters(Deployer(Scheduled Deployment),Build(testProject,1),Stage(TEST),All,MostlyHarmless)"
        )
      )
  }
}
