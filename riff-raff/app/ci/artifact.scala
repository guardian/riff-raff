package ci

import akka.agent.Agent
import ci.Context._
import ci.teamcity.TeamCity.BuildLocator
import ci.teamcity._
import controllers.Logging
import lifecycle.LifecycleWithoutApp
import org.joda.time.Duration
import rx.lang.scala.Subscription
import utils.VCSInfo

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Try

object ContinuousIntegration extends Logging {
  def getMetaData(projectName: String, buildId: String): Map[String, String] = {
    val build = Builds.all.find { build =>
      build.jobName == projectName && build.number == buildId
    }
    build.map { build =>
      val branch = Map("branch" -> build.branchName)
      val futureMap = BuildDetail(BuildLocator(id=Some(build.id))).flatMap { detailedBuild =>
        Future.sequence(detailedBuild.revision.map {
          revision =>
            revision.vcsDetails.map {
              vcsDetails =>
                branch ++
                  Map(
                    VCSInfo.REVISION -> revision.version,
                    VCSInfo.CIURL -> vcsDetails.properties("url")
                  )
            }
        }.toIterable)
          .map(_.flatten.toMap)
      } recover {
        case e => {
          log.error("Problem retrieving VCS details", e)
          Map.empty[String,String]
        }
      }
      Try(Await.result(futureMap, 30 seconds)).getOrElse(Map.empty[String, String])
    }.getOrElse(Map.empty[String,String])
  }
}

object Builds extends LifecycleWithoutApp with Logging {
  val pollingWindow = Duration.standardMinutes(conf.Configuration.teamcity.pollingWindowMinutes)
  val pollingPeriod = conf.Configuration.teamcity.pollingPeriodSeconds.seconds
  val fullUpdatePeriod = conf.Configuration.teamcity.fullUpdatePeriodSeconds.seconds

  private var subscriptions = Seq.empty[Subscription]

  def jobs: Iterable[Job] = jobsAgent.get()
  def all: List[CIBuild] = buildsAgent.get().toList
  def build(project: String, number: String) = all.find(b => b.jobName == project && b.number == number)

  val buildsAgent = Agent[Set[CIBuild]](BoundedSet(10000))
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