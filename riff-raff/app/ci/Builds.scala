package ci

import akka.agent.Agent
import ci.Context._
import controllers.Logging
import lifecycle.Lifecycle
import org.joda.time.DateTime
import rx.lang.scala.Subscription

class Builds(ciBuildPoller: CIBuildPoller) extends Lifecycle with Logging {

  private val subscriptions = Seq(
    ciBuildPoller.builds.subscribe(
      { b =>
        buildsAgent.send(_ + b)
      },
      e => log.error("Build poller failed", e)
    ),
    ciBuildPoller.jobs.subscribe { b =>
      jobsAgent.send(_ + b)
    }
  )

  def jobs: Iterable[Job] = jobsAgent.get()
  def all: List[CIBuild] = buildsAgent.get().toList
  def build(project: String, number: String) =
    all.find(b => b.jobName == project && b.number == number)
  def buildFromRevision(project: String, revision: String) = all.find {
    case build: S3Build =>
      build.jobName == project && build.revision == revision
    case _ => false
  }

  val buildsAgent = Agent[Set[CIBuild]](BoundedSet(100000))
  val jobsAgent = Agent[Set[Job]](Set())
  def successfulBuilds(jobName: String): List[CIBuild] =
    all
      .filter(_.jobName == jobName)
      .sortBy(_.startTime)(Ordering[DateTime].reverse)
  def getLastSuccessful(jobName: String): Option[String] =
    successfulBuilds(jobName).headOption.map { latestBuild =>
      latestBuild.number
    }

  def init(): Unit = {}

  def shutdown(): Unit = {
    subscriptions.foreach(_.unsubscribe())
  }
}
