package ci

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
    builds.find{ build =>
      build.buildType.name == projectName &&
      branchRE.findFirstMatchIn(build.branch).isDefined
    }
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

  def getApplicableDeployParams(builds: List[Build], configs: Iterable[ContinuousDeploymentConfig]): Iterable[DeployParameters] = {
    val enabledConfigs = configs.filter(_.enabled)
    val sortedBuilds = builds.sortBy(-_.buildId)

    val allParams = enabledConfigs.flatMap { config =>
      config.findMatch(sortedBuilds).map { build =>
        DeployParameters(
          Deployer("Continuous Deployment"),
          MagentaBuild(build.buildType.name,build.number),
          Stage(config.stage),
          RecipeName(config.recipe)
        )
      }
    }
    allParams.filter { params =>
      domains.responsibleFor(params) match {
        case Local() => true
        case _ => false
      }
    }
  }

  def newBuilds(newBuilds: List[Build]) {
    log.info("New builds to consider for deployment %s" format newBuilds)
    val deploysToRun = getApplicableDeployParams(newBuilds, Persistence.store.getContinuousDeploymentList)

    deploysToRun.foreach{ params =>
      if (conf.Configuration.continuousDeployment.enabled) {
        log.info("Triggering deploy of %s" format params.toString)
        DeployController.deploy(params)
      } else
        log.info("Would deploy %s" format params.toString)
    }
  }

}