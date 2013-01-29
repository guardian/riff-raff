package teamcity

import controllers.{Logging, DeployController}
import lifecycle.LifecycleWithoutApp
import java.util.UUID
import magenta.{Build => MagentaBuild}
import magenta.RecipeName
import magenta.DeployParameters
import magenta.Deployer
import magenta.Stage
import scala.Some
import persistence.Persistence
import deployment.DomainAction.Local
import deployment.Domains
import org.joda.time.DateTime

case class ContinuousDeploymentConfig(
  id: UUID,
  projectName: String,
  stage: String,
  recipe: String,
  branchMatcher:Option[String],
  enabled: Boolean,
  user: String,
  lastEdited: DateTime = new DateTime()
) {
  lazy val branchRE = branchMatcher.map(re => "^%s$".format(re).r).getOrElse(".*".r)
  def findMatch(builds: List[Build]): Option[Build] = {
    builds.find(build => branchRE.findFirstMatchIn(build.branch).isDefined)
  }
}

object ContinuousDeployment extends LifecycleWithoutApp {

  var buildWatcher: Option[ContinuousDeployment] = None

  def init() {
    if (buildWatcher.isEmpty) {
      buildWatcher = Some(new ContinuousDeployment(Domains))
      buildWatcher.foreach(TeamCity.subscribe)
    }
  }

  def shutdown() {
    buildWatcher.foreach(TeamCity.unsubscribe)
    buildWatcher = None
  }
}

class ContinuousDeployment(domains: Domains) extends BuildWatcher with Logging {

  type ProjectCdMap = Map[String, Set[ContinuousDeploymentConfig]]

  def createContinuousDeploymentMap(configs: Iterable[ContinuousDeploymentConfig]): ProjectCdMap = {
    configs.filter(_.enabled).groupBy(_.projectName).mapValues(_.toSet)
  }

  def getLatestNewBuilds(previousMap: BuildTypeMap, newMap: BuildTypeMap, cdMap: ProjectCdMap): Map[BuildType, List[Build]] = {
    if (previousMap.isEmpty)
      Map.empty
    else {
      val previousLatestBuild = previousMap.latestBuildId()

      newMap.filterBuilds(
        buildType => cdMap.get(buildType.name).isDefined,
        builds => builds.filter( build => build.buildId > previousLatestBuild ).sortBy(_.buildId).reverse
      )
    }
  }

  def getApplicableDeployParams(buildMap: Map[BuildType,List[Build]], cdMap: ProjectCdMap): Iterable[DeployParameters] = {
    buildMap.flatMap { case (buildType, builds) =>
      val continuousDeployConfigs = cdMap(buildType.name)
      continuousDeployConfigs.flatMap { continuousDeployConfig =>
        continuousDeployConfig.findMatch(builds).map { build =>
          DeployParameters(
            Deployer("Continuous Deployment"),
            MagentaBuild(buildType.name,build.number),
            Stage(continuousDeployConfig.stage),
            RecipeName(continuousDeployConfig.recipe)
          )
        }
      }.filter { params =>
        domains.responsibleFor(params) match {
          case Local() => true
          case _ => false
        }
      }
    }
  }

  def change(previousMap: BuildTypeMap, currentMap: BuildTypeMap) {
    log.info("Checking for any new builds to deploy")
    val cdMap = createContinuousDeploymentMap(Persistence.store.getContinuousDeploymentList)

    val newBuilds = getLatestNewBuilds(previousMap, currentMap, cdMap)
    log.info("Filtered build map %s" format newBuilds)

    val deploysToRun = getApplicableDeployParams(newBuilds, cdMap)

    deploysToRun.foreach{ params =>
      if (conf.Configuration.continuousDeployment.enabled) {
        log.info("Triggering deploy of %s" format params.toString)
        DeployController.deploy(params)
      } else
        log.info("Would deploy %s" format params.toString)
    }
  }

}