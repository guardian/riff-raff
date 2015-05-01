package ci

import akka.agent.Agent
import ci.Context._
import controllers.Logging
import lifecycle.LifecycleWithoutApp
import rx.lang.scala.Subscription

object Builds extends LifecycleWithoutApp with Logging {

  private var subscriptions = Seq.empty[Subscription]

  def jobs: Iterable[Job] = jobsAgent.get()
  def all: List[CIBuild] = buildsAgent.get().toList
  def build(project: String, number: String) = all.find(b => b.jobName == project && b.number == number)

  val buildsAgent = Agent[Set[CIBuild]](BoundedSet(100000))
  val jobsAgent = Agent[Set[Job]](Set())
  def successfulBuilds(jobName: String): List[CIBuild] = all.filter(_.jobName == jobName).sortBy(- _.id)
  def getLastSuccessful(jobName: String): Option[String] =
    successfulBuilds(jobName).headOption.map{ latestBuild =>
      latestBuild.number
    }

  def init() {
    subscriptions = Seq(
      CIBuild.builds.subscribe { b =>
        buildsAgent.send(_ + b)
      },
      CIBuild.jobs.subscribe { b =>
        jobsAgent.send(_ + b)
      }
    )
  }

  def shutdown() {
    subscriptions.foreach(_.unsubscribe())
  }
}