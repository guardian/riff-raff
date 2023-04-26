package ci

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.joda.time.DateTime
import magenta._
import magenta.DeployParameters
import magenta.Deployer
import magenta.Stage
import magenta.Strategy.MostlyHarmless

import java.util.UUID
import scala.util.{Failure, Success}

class ContinuousDeploymentTest extends AnyFlatSpec with Matchers {
  "Continuous Deployment" should "create deploy parameters for a set of matching builds" in {
    val build = S3Build(
      45397,
      "tools::deploy",
      "45397",
      "main",
      "71",
      new DateTime(2013, 1, 25, 14, 42, 47),
      "",
      "",
      buildTool = None
    )
    val matchingProjectAndBranchEnabled = ContinuousDeploymentConfig(
      UUID.randomUUID(),
      build.jobName,
      "PROD",
      "(main|release)",
      Trigger.SuccessfulBuild,
      "Test user"
    )
    val matchingProjectAndBranchDisabled = ContinuousDeploymentConfig(
      UUID.randomUUID(),
      build.jobName,
      "QA",
      "(main|release)",
      Trigger.Disabled,
      "Test user"
    )
    val wrongProject = ContinuousDeploymentConfig(
      UUID.randomUUID(),
      "tools::someotherproject",
      "CODE",
      "main",
      Trigger.SuccessfulBuild,
      "Test user"
    )
    val cdConfigs = Set(matchingProjectAndBranchEnabled, matchingProjectAndBranchDisabled, wrongProject)

    val params = ContinuousDeployment
      .getMatchesForSuccessfulBuilds(build, cdConfigs)
      .map(ContinuousDeployment.getDeployParams(_))
      .toSet

    params.size should be(1)
    params should be(
      Set(
        DeployParameters(
          Deployer("Continuous Deployment"),
          Build("tools::deploy", "71"),
          Stage("PROD"),
          updateStrategy = MostlyHarmless
        )
      )
    )
  }

  it should "return nothing if no matches" in {
    val build = S3Build(
      45401,
      "tools::deploy2",
      "45401",
      "other_branch_name",
      "393",
      new DateTime(2013, 1, 25, 15, 34, 47),
      "",
      "",
      buildTool = Some("guardian/actions-riff-raff")
    )
    val projectDoesNotMatch = ContinuousDeploymentConfig(
      UUID.randomUUID(),
      "tools::deploy",
      "PROD",
      "main",
      Trigger.SuccessfulBuild,
      "Test user"
    )
    val branchDoesNotMatch = ContinuousDeploymentConfig(
      UUID.randomUUID(),
      build.jobName,
      "PROD",
      "branch_name",
      Trigger.SuccessfulBuild,
      "Test user"
    )
    val cdDisabled = ContinuousDeploymentConfig(
      UUID.randomUUID(),
      build.jobName,
      "PROD",
      build.branchName,
      Trigger.Disabled,
      "Test user"
    )

    val params = ContinuousDeployment
      .getMatchesForSuccessfulBuilds(build, Set(projectDoesNotMatch, branchDoesNotMatch, cdDisabled))
      .map(ContinuousDeployment.getDeployParams(_))
      .toSet

    params should be(Set())
  }

  it should "take account of branch" in {
    val build = S3Build(
      45400,
      "tools::deploy2",
      "45400",
      "main",
      "392",
      new DateTime(2013, 1, 25, 15, 34, 47),
      "",
      "",
      buildTool = None
    )
    val nonMatchingBranch = ContinuousDeploymentConfig(
      UUID.randomUUID(),
      build.jobName,
      "QA",
      "some_other_branch",
      Trigger.SuccessfulBuild,
      "Test user"
    )
    val matchingBranch = ContinuousDeploymentConfig(
      UUID.randomUUID(),
      build.jobName,
      "PROD",
      build.branchName,
      Trigger.SuccessfulBuild,
      "Test user"
    )

    val params = ContinuousDeployment
      .getMatchesForSuccessfulBuilds(build, Set(nonMatchingBranch, matchingBranch))
      .map(ContinuousDeployment.getDeployParams(_))
      .toSet

    params should be(
      Set(
        DeployParameters(
          Deployer("Continuous Deployment"),
          Build("tools::deploy2", "392"),
          Stage("PROD"),
          updateStrategy = MostlyHarmless
        )
      )
    )
  }

  it should "retry until finds success" in {
    var i = 0
    def failingFun = {
      if (i < 3) {
        i = i + 1
        throw new RuntimeException(s"erk $i")
      } else i
    }

    val success = ContinuousDeployment.retryUpTo(4)(failingFun)
    success should be(Success(3))

    i = 0
    val failure = ContinuousDeployment.retryUpTo(2)(failingFun)
    failure.isFailure should be(true)
  }
}
