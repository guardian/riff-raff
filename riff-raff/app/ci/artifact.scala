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


object `package` {
  implicit def listOfBuild2helpers(builds: List[TeamcityBuild]) = new {
    def buildTypes: Set[teamcity.BuildType] = builds.map(_.buildType).toSet
  }

  implicit def futureIterable2FlattenMap[A](futureIterable: Future[Iterable[A]]) = new {
    def flatFutureMap[B](p: A => Future[Iterable[B]]):Future[Iterable[B]] = {
      futureIterable.flatMap { iterable =>
        val newFutures:Iterable[Future[Iterable[B]]] = iterable.map(p)
        Future.sequence(newFutures).map(_.flatten)
      }
    }
  }
}

object ContinuousIntegration {
  def getMetaData(projectName: String, buildId: String): Map[String, String] = {
    val build = TeamCityBuilds.builds.find { build =>
      build.projectName == projectName && build.number == buildId
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

  private var buildSubscription: Option[Subscription] = None

  def builds: List[CIBuild] = buildsAgent.get().toList
  def build(project: String, number: String) = builds.find(b => b.projectName == project && b.number == number)
  def buildTypes: Iterable[CIBuild] = buildsAgent.get().groupBy(b => b.projectName).map{
    case (_, builds) => builds.head
  }
  val buildsAgent = Agent[Set[CIBuild]](BoundedSet(10000))
  def successfulBuilds(projectName: String): List[CIBuild] = builds.filter(_.projectName == projectName).sortBy(- _.id)
  def getLastSuccessful(projectName: String): Option[String] =
    successfulBuilds(projectName).headOption.map{ latestBuild =>
      latestBuild.number
    }

  def init() {
    buildSubscription = Some(CIBuild.teamCityBuilds.subscribe { b =>
      buildsAgent.send(_ + b)
    })
  }

  def shutdown() {
    buildSubscription.foreach(_.unsubscribe())
  }
}