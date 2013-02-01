package test

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import ci.{ContinuousDeploymentConfig, Build, BuildType, ContinuousDeployment}
import deployment.Domains
import java.util.UUID
import magenta.{Build => MagentaBuild}
import magenta.DeployParameters
import magenta.Deployer
import magenta.Stage
import magenta.RecipeName

class ContinuousDeploymentTest extends FlatSpec with ShouldMatchers with DomainsTestHelper {

  "Continuous Deployment" should "create deploy parameters for a set of builds" in {
    val params = continuousDeployment.getApplicableDeployParams(newBuildsList, contDeployConfigs).toSet
    params.size should be(2)
    params should be(Set(
      DeployParameters(Deployer("Continuous Deployment"), MagentaBuild("tools::deploy2", "392"), Stage("QA"), RecipeName("default")),
      DeployParameters(Deployer("Continuous Deployment"), MagentaBuild("tools::deploy", "71"), Stage("PROD"), RecipeName("default"))
    ))
  }

  it should "work out the latest build for different branches" in {
    val params = continuousDeployment.getApplicableDeployParams(newBuildsList, contDeployBranchConfigs).toSet
    val expected = Set(
      DeployParameters(Deployer("Continuous Deployment"), MagentaBuild("tools::deploy2", "392"), Stage("QA"), RecipeName("default")),
      DeployParameters(Deployer("Continuous Deployment"), MagentaBuild("tools::deploy2", "391"), Stage("PROD"), RecipeName("default")),
      DeployParameters(Deployer("Continuous Deployment"), MagentaBuild("tools::deploy", "71"), Stage("PROD"), RecipeName("default"))
    )
    params.size should be(expected.size)
    params should be(expected)
  }

  it should "filter stages according to the domains configuration" in {
    val params = nonProdCD.getApplicableDeployParams(List(td2B390), contDeployConfigs)
    params.size should be(1)
    params.head should be(DeployParameters(Deployer("Continuous Deployment"), MagentaBuild("tools::deploy2", "390"), Stage("QA"), RecipeName("default")))
  }

  /* Test types */

  val tdProdEnabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy", "PROD", "default", None, true, "Test user")
  val tdCodeDisabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy", "CODE", "default", None, false, "Test user")
  val td2ProdDisabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy2", "PROD", "default", None, false, "Test user")
  val td2QaEnabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy2", "QA", "default", None, true, "Test user")
  val td2QaBranchEnabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy2", "QA", "default", Some("branch"), true, "Test user")
  val td2ProdBranchEnabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy2", "PROD", "default", Some("master"), true, "Test user")
  val contDeployConfigs = Seq(tdProdEnabled, tdCodeDisabled, td2ProdDisabled, td2QaEnabled)
  val contDeployBranchConfigs = Seq(tdProdEnabled, tdCodeDisabled, td2ProdDisabled, td2QaBranchEnabled, td2ProdBranchEnabled)

  val nonProdConfig = Map(
    "domains.enabled" -> "true",
    "domains.identity" -> "test2",
    "domains.test1.responsibility.stage.regex" -> "^PROD$",
    "domains.test1.urlPrefix" -> "https://test1",
    "domains.test2.responsibility.stage.regex" -> "^PROD$",
    "domains.test2.responsibility.stage.invertRegex" -> "true",
    "domains.test2.urlPrefix" -> "https://test2"
  )
  val nonProdDomainInstance = new Domains(testDomainsConfiguration(nonProdConfig))
  val nonProdCD = new ContinuousDeployment(nonProdDomainInstance)

  val config = Map("domains.enabled" -> "false")
  val domainInstance = new Domains(testDomainsConfiguration(config))
  val continuousDeployment = new ContinuousDeployment(domainInstance)

  val tdBT = BuildType("bt204", "tools::deploy")
  val tdB70 = Build(45394, tdBT, "70", "20130124T144247+0000", "master")
  val tdB71 = Build(45397, tdBT, "71", "20130125T144247+0000", "master")

  val td2BT = BuildType("bt205", "tools::deploy2")
  val td2B390 = Build(45395, td2BT, "390", "20130124T144347+0000", "branch")
  val td2B391 = Build(45396, td2BT, "391", "20130125T144447+0000", "master")
  val td2B392 = Build(45400, td2BT, "392", "20130125T153447+0000", "branch")

  val newBuildsList = List( tdB70, tdB71, td2B390, td2B391, td2B392 )
}
