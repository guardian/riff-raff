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

  "Continuous Deployment" should "create deploy parameters for a set of builds" in {
    val params = ContinuousDeployment
      .getMatchesForSuccessfulBuilds(tdB71, contDeployConfigs)
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
    val params = ContinuousDeployment
      .getMatchesForSuccessfulBuilds(otherBranch, contDeployBranchConfigs)
      .map(ContinuousDeployment.getDeployParams(_))
      .toSet
    params should be(Set())
  }

  it should "take account of branch" in {
    val params = ContinuousDeployment
      .getMatchesForSuccessfulBuilds(td2B392, contDeployBranchConfigs)
      .map(ContinuousDeployment.getDeployParams(_))
      .toSet
    params should be(
      Set(
        DeployParameters(
          Deployer("Continuous Deployment"),
          Build("tools::deploy2", "392"),
          Stage("QA"),
          updateStrategy = MostlyHarmless
        )
      )
    )
  }

  /* Test types */

  val tdProdEnabled = ContinuousDeploymentConfig(
    UUID.randomUUID(),
    "tools::deploy",
    "PROD",
    None,
    Trigger.SuccessfulBuild,
    "Test user"
  )
  val tdCodeDisabled = ContinuousDeploymentConfig(
    UUID.randomUUID(),
    "tools::deploy",
    "CODE",
    None,
    Trigger.Disabled,
    "Test user"
  )
  val td2ProdDisabled = ContinuousDeploymentConfig(
    UUID.randomUUID(),
    "tools::deploy2",
    "PROD",
    None,
    Trigger.Disabled,
    "Test user"
  )
  val td2QaEnabled = ContinuousDeploymentConfig(
    UUID.randomUUID(),
    "tools::deploy2",
    "QA",
    None,
    Trigger.SuccessfulBuild,
    "Test user"
  )
  val td2QaBranchEnabled = ContinuousDeploymentConfig(
    UUID.randomUUID(),
    "tools::deploy2",
    "QA",
    Some("branch"),
    Trigger.SuccessfulBuild,
    "Test user"
  )
  val td2ProdBranchEnabled = ContinuousDeploymentConfig(
    UUID.randomUUID(),
    "tools::deploy2",
    "PROD",
    Some("master"),
    Trigger.SuccessfulBuild,
    "Test user"
  )
  val contDeployConfigs =
    Seq(tdProdEnabled, tdCodeDisabled, td2ProdDisabled, td2QaEnabled)
  val contDeployBranchConfigs = Seq(
    tdProdEnabled,
    tdCodeDisabled,
    td2ProdDisabled,
    td2QaBranchEnabled,
    td2ProdBranchEnabled
  )

  val tdB71 = S3Build(
    45397,
    "tools::deploy",
    "45397",
    "branch",
    "71",
    new DateTime(2013, 1, 25, 14, 42, 47),
    "",
    "",
    buildTool = None
  )
  val td2B392 = S3Build(
    45400,
    "tools::deploy2",
    "45400",
    "branch",
    "392",
    new DateTime(2013, 1, 25, 15, 34, 47),
    "",
    "",
    buildTool = None
  )
  val otherBranch = S3Build(
    45401,
    "tools::deploy2",
    "45401",
    "other",
    "393",
    new DateTime(2013, 1, 25, 15, 34, 47),
    "",
    "",
    buildTool = Some("guardian/actions-riff-raff")
  )

  val olderBiggerID = S3Build(
    2,
    "tools::deploy",
    "2",
    "branch",
    "71",
    new DateTime(2023, 6, 27, 14, 42, 14),
    "",
    "",
    buildTool = None
  )
  val newerSmallerID = S3Build(
    1,
    "tools::deploy",
    "1",
    "branch",
    "71",
    new DateTime(2023, 6, 27, 14, 43, 14),
    "",
    "",
    buildTool = None
  )

  it should "order builds by start time (not build ID)" in {
    List(newerSmallerID, olderBiggerID).max(CIBuild.ord).id should be(1)
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
