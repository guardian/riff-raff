package ci

import org.scalatest.FunSuite
import rx.lang.scala.Observable
import ci.teamcity.{Job, Project, BuildType}
import org.joda.time.DateTime
import org.scalatest.matchers.ShouldMatchers

class ReactiveDeploymentTest extends FunSuite with ShouldMatchers {
  test("should not try to deploy items already built at startup") {
    val jobs = Observable.items(BuildType("job1", "job", Project("", "project")))
    val allBuilds = (job: Job) => Observable.items(Seq(ciBuild(1), ciBuild(2)))
    val newBuilds = (job: Job) => Observable.items(ciBuild(2))

    ReactiveDeployment.buildCandidates(jobs, allBuilds, newBuilds).toBlockingObservable.toList should be(Nil)
  }

  test("should only try to deploy latest for job and branch") {
    val jobs = Observable.items(BuildType("job1", "job", Project("", "project")))
    val allBuilds = (job: Job) => Observable.items(Seq(ciBuild(1), ciBuild(2)))
    val newBuilds = (job: Job) => Observable.items(ciBuild(2), ciBuild(5), ciBuild(4), ciBuild(3, "other"))

    ReactiveDeployment.buildCandidates(jobs, allBuilds, newBuilds).toBlockingObservable.toList should be(
      List(ciBuild(5), ciBuild(3, "other"))
    )
  }

  def ciBuild(num: Long, branch: String = "master") = new CIBuild {
    def id = num
    def startTime = DateTime.now
    def number = num.toString
    def jobName = "project::job"
    def branchName = branch
    def jobId = "job1"

    override def equals(other: scala.Any) = other match {
      case o: CIBuild => o.id == id
      case _ => false
    }
    override def hashCode() = id.toInt
    override def toString = s"CIBuild($jobName, $number)"
  }
}
