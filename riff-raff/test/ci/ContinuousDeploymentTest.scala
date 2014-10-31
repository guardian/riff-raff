package ci

import org.scalatest.FlatSpec
import rx.lang.scala.Observable
import ci.teamcity.{BuildSummary, Job, Project, BuildType}
import org.joda.time.DateTime
import org.scalatest.matchers.ShouldMatchers
import magenta._
import magenta.DeployParameters
import magenta.Deployer
import magenta.Stage
import java.util.UUID
import java.net.URL

class ContinuousDeploymentTest extends FlatSpec with ShouldMatchers {
  "Continuous Deployment" should "not try to deploy items already built at startup" in {
    val jobs = Observable.items(BuildType("job1", "job", Project("", "project")))
    val allBuilds = (job: Job) => Observable.items(Seq(ciBuild(1), ciBuild(2)))
    val newBuilds = (job: Job) => Observable.items(ciBuild(2))

    ContinuousDeployment.buildCandidates(jobs, allBuilds, newBuilds).toBlockingObservable.toList should be(Nil)
  }

  it should "not try to deploy the same build twice" in {
    val jobs = Observable.items(BuildType("job1", "job", Project("", "project")))
    val allBuilds = (job: Job) => Observable.items(Seq(ciBuild(1), ciBuild(2)))
    val newBuilds = (job: Job) => Observable.items(ciBuild(2), ciBuild(3), ciBuild(3))

    ContinuousDeployment.buildCandidates(jobs, allBuilds, newBuilds).toBlockingObservable.toList should be(List(ciBuild(3)))
  }

  it should "only try to deploy latest for job and branch" in {
    val jobs = Observable.items(BuildType("job1", "job", Project("", "project")))
    val allBuilds = (job: Job) => Observable.items(Seq(ciBuild(1), ciBuild(2)))
    val newBuilds = (job: Job) => Observable.items(ciBuild(2), ciBuild(5), ciBuild(4), ciBuild(3, "other"))

    ContinuousDeployment.buildCandidates(jobs, allBuilds, newBuilds).toBlockingObservable.toList should be(
      List(ciBuild(5), ciBuild(3, "other"))
    )
  }

  "Continuous Deployment" should "create deploy parameters for a set of builds" in {
    val params = ContinuousDeployment.getMatchesForSuccessfulBuilds(tdB71, contDeployConfigs).map(ContinuousDeployment.getDeployParams(_)).toSet
    params.size should be(1)
    params should be(Set(
      DeployParameters(Deployer("Continuous Deployment"), Build("tools::deploy", "71"), Stage("PROD"), RecipeName("default"))
    ))
  }

  it should "return nothing if no matches" in {
    val params = ContinuousDeployment.getMatchesForSuccessfulBuilds(otherBranch, contDeployBranchConfigs).map(ContinuousDeployment.getDeployParams(_)).toSet
    params should be(Set())
  }

  it should "take account of branch" in {
    val params = ContinuousDeployment.getMatchesForSuccessfulBuilds(td2B392, contDeployBranchConfigs).map(ContinuousDeployment.getDeployParams(_)).toSet
    params should be(Set(DeployParameters(Deployer("Continuous Deployment"), Build("tools::deploy2", "392"), Stage("QA"), RecipeName("default"))))
  }

  def ciBuild(num: Long, branch: String = "master") = new CIBuild {
    def id = num
    def startTime = DateTime.now
    def number = num.toString
    def jobName = "project::job"
    def branchName = branch
    def jobId = "job1"
    def tags: List[String] = Nil

    override def equals(other: scala.Any) = other match {
      case o: CIBuild => o.id == id
      case _ => false
    }
    override def hashCode() = id.toInt
    override def toString = s"CIBuild($jobName, $number)"
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

  val tdBT = BuildType("bt204", "deploy", Project("project1", "tools"))
  val tdB71 = BuildSummary(45397, "71", tdBT.id, "SUCCESS", "master", new DateTime(2013,1,25,14,42,47), Nil, tdBT)

  val td2BT = BuildType("bt205", "deploy2", Project("project1", "tools"))
  val td2B392 = BuildSummary(45400, "392", td2BT.id, "SUCCESS", "branch", new DateTime(2013,1,25,15,34,47), Nil, td2BT)
  val otherBranch = BuildSummary(45401, "393", td2BT.id, "SUCCESS", "other", new DateTime(2013,1,25,15,34,47), Nil, td2BT)
}
