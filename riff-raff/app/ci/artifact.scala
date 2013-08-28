package ci

import teamcity._
import teamcity.TeamCity.{BuildTypeLocator, BuildLocator}
import utils.{VCSInfo, Update, PeriodicScheduledAgentUpdate, ScheduledAgent}
import scala.concurrent.duration._
import org.joda.time.{Duration, DateTime}
import controllers.Logging
import scala.Predef._
import collection.mutable
import lifecycle.LifecycleWithoutApp
import scala.Some
import magenta.DeployParameters
import concurrent.Future
import concurrent.Await
import concurrent.ExecutionContext.Implicits.global

object `package` {
  implicit def listOfBuild2helpers(builds: List[Build]) = new {
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
      build.buildType.fullName == projectName && build.number == buildId
    }
    build.map { build =>
      val branch = Map("branch" -> build.branchName)
      val futureMap = build.detail.flatMap { detailedBuild =>
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

trait BuildWatcher {
  def newBuilds(builds: List[Build])
}

trait LocatorWatcher {
  def locator: BuildLocator
  def newBuilds(builds: List[Build])
}

trait BuildTracker {
  def builds: List[Build]
  def shutdown()
}

class BuildLocatorTracker(locator: BuildLocator,
                          fullUpdatePeriod: FiniteDuration,
                          pollingPeriod: FiniteDuration,
                          pollingWindow: Duration,
                          notify: List[Build] => Unit) extends BuildTracker with Logging {

  private val fullUpdate = PeriodicScheduledAgentUpdate[List[Build]](0 seconds, fullUpdatePeriod) { currentBuilds =>
    val builds = Await.result(getBuilds, 1 minute)
    if (!currentBuilds.isEmpty) buildAgent.queueUpdate(Update(notify((builds.toSet diff currentBuilds.toSet).toList)))
    builds
  }

  private val incrementalUpdate = PeriodicScheduledAgentUpdate[List[Build]](1 minute, pollingPeriod) { currentBuilds =>
    if (currentBuilds.isEmpty) {
      log.warn("No builds yet, aborting incremental update")
      currentBuilds
    } else {
      Await.result(getNewBuilds(currentBuilds).map { newBuilds =>
        if (newBuilds.isEmpty)
          currentBuilds
        else {
          buildAgent.queueUpdate(Update(notify(newBuilds)))
          (currentBuilds ++ newBuilds).sortBy(-_.id)
        }
      },pollingPeriod)
    }
  }

  private val buildAgent: ScheduledAgent[List[Build]] = ScheduledAgent[List[Build]](Nil, fullUpdate, incrementalUpdate)
  def builds: List[Build] = buildAgent.apply()

  def shutdown() {
    buildAgent.shutdown()
  }

  def getBuilds: Future[List[Build]] = {
    log.debug(s"Getting builds matching $locator")
    val buildTypes = BuildTypeLocator.list
    buildTypes.flatMap { fulfilledBuildTypes =>
      log.debug(s"Found ${fulfilledBuildTypes.size} buildTypes")
      val allBuilds = Future.sequence(fulfilledBuildTypes.map(_.builds(locator))).map(_.flatten)
      allBuilds.map { result =>
        log.info("Finished updating TC information (found %d buildTypes and %d successful builds)" format(fulfilledBuildTypes.size, result.size))
        result
      }
    }
  }

  def getNewBuilds(currentBuilds:List[Build]): Future[List[Build]] = {
    val knownBuilds = currentBuilds.map(_.id).toSet
    val buildTypeMap = currentBuilds.map(b => b.buildType.id -> b.buildType).toMap
    val getBuildType = (buildTypeId:String) => {
      buildTypeMap.get(buildTypeId).orElse {
        buildAgent.queueUpdate(fullUpdate)
        log.warn("Unknown build type %s, queuing complete refresh" format buildTypeId)
        None
      }
    }
    val pollingWindowStart = new DateTime().minus(pollingWindow)
    log.info("Querying TC for all builds since %s" format pollingWindowStart)
    val builds = BuildSummary.listWithLookup(BuildLocator.sinceDate(pollingWindowStart).status("SUCCESS"), getBuildType)
    builds.map { builds =>
      log.debug("Found %d builds since %s" format (builds.size, pollingWindowStart))
      val newBuilds = builds.filterNot(build => knownBuilds.contains(build.id))
      log.info("Discovered builds: \n%s" format newBuilds.mkString("\n"))
      newBuilds
    }
  }
}

object TeamCityBuilds extends LifecycleWithoutApp with Logging {
  val pollingWindow = Duration.standardMinutes(conf.Configuration.teamcity.pollingWindowMinutes)
  val pollingPeriod = conf.Configuration.teamcity.pollingPeriodSeconds.seconds
  val fullUpdatePeriod = conf.Configuration.teamcity.fullUpdatePeriodSeconds.seconds

  private val listeners = mutable.Buffer[BuildWatcher]()
  def subscribe(sink: BuildWatcher) { listeners += sink }
  def unsubscribe(sink: BuildWatcher) { listeners -= sink }

  def subscribe(sink: LocatorWatcher) {
    if (TeamCityWS.teamcityURL.isDefined) {
      locatorTrackers += sink -> new BuildLocatorTracker(
          sink.locator.status("SUCCESS"),
          fullUpdatePeriod,
          pollingPeriod,
          pollingWindow,
          builds => sink.newBuilds(builds)
        )
    }
  }
  def unsubscribe(sink: LocatorWatcher) {
    locatorTrackers -= sink
  }

  def notifyNewBuilds(newBuilds: List[Build]) = {
    log.info("Notifying listeners")
    listeners.foreach{ listener =>
      try listener.newBuilds(newBuilds)
      catch {
        case e:Exception => log.error("BuildWatcher threw an exception", e)
      }
    }
  }

  private var successfulBuildTracker:Option[BuildLocatorTracker] = None
  private var locatorTrackers = Map.empty[LocatorWatcher, BuildLocatorTracker]

  def builds: List[Build] = successfulBuildTracker.map(_.builds).getOrElse(Nil)
  def build(project: String, number: String) = builds.find(b => b.buildType.fullName == project && b.number == number)
  def buildTypes: Set[BuildType] = builds.buildTypes

  def successfulBuilds(projectName: String): List[Build] = builds.filter(_.buildType.fullName == projectName)

  def transformLastSuccessful(params: DeployParameters): DeployParameters = {
    if (params.build.id != "lastSuccessful")
      params
    else {
      val builds = successfulBuilds(params.build.projectName)
      builds.headOption.map{ latestBuild =>
        params.copy(build = params.build.copy(id = latestBuild.number))
      }.getOrElse(params)
    }
  }

  def init() {
    if (TeamCityWS.teamcityURL.isDefined) {
      successfulBuildTracker = Some(
        new BuildLocatorTracker(
          BuildLocator.status("SUCCESS"),
          fullUpdatePeriod,
          pollingPeriod,
          pollingWindow,
          notifyNewBuilds
        )
      )
    }
  }

  def shutdown() { successfulBuildTracker.foreach(_.shutdown()) }
}

