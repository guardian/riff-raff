package test

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import ci.{ReactiveDeployment, Trigger, ContinuousDeploymentConfig}
import java.util.UUID
import magenta.{Build => MagentaBuild}
import magenta.DeployParameters
import magenta.Deployer
import magenta.Stage
import magenta.RecipeName
import ci.teamcity.{Project, BuildType, BuildSummary}
import java.net.URL
import org.joda.time.DateTime

class ContinuousDeploymentTest extends FlatSpec with ShouldMatchers {

  "Continuous Deployment" should "create deploy parameters for a set of builds" in {
    val params = ReactiveDeployment.getMatchesForSuccessfulBuilds(tdB71, contDeployConfigs).map(ReactiveDeployment.getDeployParams(_)).toSet
    params.size should be(1)
    params should be(Set(
      DeployParameters(Deployer("Continuous Deployment"), MagentaBuild("tools::deploy", "71"), Stage("PROD"), RecipeName("default"))
    ))
  }

  it should "return nothing if no matches" in {
    val params = ReactiveDeployment.getMatchesForSuccessfulBuilds(otherBranch, contDeployBranchConfigs).map(ReactiveDeployment.getDeployParams(_)).toSet
    params should be(Set())
  }

  it should "take account of branch" in {
    val params = ReactiveDeployment.getMatchesForSuccessfulBuilds(td2B392, contDeployBranchConfigs).map(ReactiveDeployment.getDeployParams(_)).toSet
    params should be(Set(DeployParameters(Deployer("Continuous Deployment"), MagentaBuild("tools::deploy2", "392"), Stage("QA"), RecipeName("default"))))
  }

  /* Test types */

  val tdProdEnabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy", "PROD", "default", None, Trigger.SuccessfulBuild, "Test user")
  val tdCodeDisabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy", "CODE", "default", None, Trigger.Disabled, "Test user")
  val td2ProdDisabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy2", "PROD", "default", None, Trigger.Disabled, "Test user")
  val td2QaEnabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy2", "QA", "default", None, Trigger.SuccessfulBuild, "Test user")
  val td2QaBranchEnabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy2", "QA", "default", Some("branch"), Trigger.SuccessfulBuild, "Test user")
  val td2ProdBranchEnabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy2", "PROD", "default", Some("master"), Trigger.SuccessfulBuild, "Test user")
  val contDeployConfigs = Seq(tdProdEnabled, tdCodeDisabled, td2ProdDisabled, td2QaEnabled)
  val contDeployBranchConfigs = Seq(tdProdEnabled, tdCodeDisabled, td2ProdDisabled, td2QaBranchEnabled, td2ProdBranchEnabled)

  val url = new URL("http://riffraff.gnl/test")

  val tdBT = BuildType("bt204", "deploy", Project("project1", "tools"))
  val tdB71 = BuildSummary(45397, "71", tdBT.id, "SUCCESS", url, "master", None, new DateTime(2013,1,25,14,42,47), tdBT)

  val td2BT = BuildType("bt205", "deploy2", Project("project1", "tools"))
  val td2B392 = BuildSummary(45400, "392", td2BT.id, "SUCCESS", url, "branch", None, new DateTime(2013,1,25,15,34,47), td2BT)
  val otherBranch = BuildSummary(45401, "393", td2BT.id, "SUCCESS", url, "other", None, new DateTime(2013,1,25,15,34,47), td2BT)
}
