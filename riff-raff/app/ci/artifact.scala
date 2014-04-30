package ci

import teamcity._
import teamcity.TeamCity.{BuildTypeLocator, BuildLocator}
import utils.{VCSInfo, PeriodicScheduledAgentUpdate, ScheduledAgent}
import scala.concurrent.duration._
import org.joda.time.{Duration, DateTime}
import controllers.Logging
import lifecycle.LifecycleWithoutApp
import concurrent.{ Future, Promise, promise, Await }
import play.api.Logger
import akka.agent.Agent
import Context._
import scala.util.Try
import rx.lang.scala.{Observable, Subscription}

object ContinuousIntegration {
  def getMetaData(projectName: String, buildId: String): Map[String, String] = {
    val build = TeamCityBuilds.builds.find { build =>
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
        case _ => Map.empty[String,String]
      }
      Await.result(futureMap, 5 seconds)
    }.getOrElse(Map.empty[String,String])
  }
}

object TeamCityBuilds extends LifecycleWithoutApp with Logging {
  val pollingWindow = Duration.standardMinutes(conf.Configuration.teamcity.pollingWindowMinutes)
  val pollingPeriod = conf.Configuration.teamcity.pollingPeriodSeconds.seconds
  val fullUpdatePeriod = conf.Configuration.teamcity.fullUpdatePeriodSeconds.seconds

  private var subscriptions = Seq.empty[Subscription]

  def jobs: Iterable[Job] = jobsAgent.get()
  def builds: List[CIBuild] = buildsAgent.get().toList
  def build(project: String, number: String) = builds.find(b => b.jobName == project && b.number == number)

  val buildsAgent = Agent[Set[CIBuild]](BoundedSet(10000))
  val jobsAgent = Agent[Set[Job]](Set())
  def successfulBuilds(jobName: String): List[CIBuild] = builds.filter(_.jobName == jobName).sortBy(- _.id)
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