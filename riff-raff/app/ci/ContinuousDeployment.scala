package ci

import conf.Config
import controllers.Logging
import deployment.{ContinuousDeploymentRequestSource, Deployments}
import lifecycle.Lifecycle
import magenta.{DeployParameters, Deployer, Stage}
import persistence.ContinuousDeploymentConfigRepository.getContinuousDeploymentList
import rx.lang.scala.{Observable, Subscription}
import utils.{ChangeFreeze, Retriable}

import scala.util.control.NonFatal

class ContinuousDeployment(buildPoller: CIBuildPoller, deployments: Deployments) extends Lifecycle with Logging {
  import ContinuousDeployment._

  val builds: Observable[CIBuild] = buildCandidates(buildPoller.newBuilds)

  val sub: Subscription = builds.subscribe { b =>
    getMatchesForSuccessfulBuilds(b, cdConfigs) foreach  { x =>
      runDeploy(getDeployParams(x))
    }
  }

  def cdConfigs: List[ContinuousDeploymentConfig] = retryUpTo(5)(getContinuousDeploymentList()).getOrElse{
    log.error("Failed to retrieve CD configs")
    Nil
  }

  def buildCandidates(builds: Observable[CIBuild]): Observable[CIBuild] =
    (for {
      (_, buildsPerJobAndBranch) <- builds.groupBy(b => (b.jobName, b.branchName))
      build <- GreatestSoFar(buildsPerJobAndBranch.distinct)
    } yield build).onErrorResumeNext(e => {
      log.error("Problem polling builds for ContinuousDeployment", e)
      buildCandidates(buildPoller.newBuilds)
    })

  def init() {}

  def shutdown() {
    sub.unsubscribe()
  }

  def runDeploy(params: DeployParameters) {
    if (Config.continuousDeployment.enabled) {
      if (!ChangeFreeze.frozen(params.stage.name)) {
        log.info(s"Triggering deploy of ${params.toString}")
        try {
          deployments.deploy(params, requestSource = ContinuousDeploymentRequestSource).left.foreach { error =>
            log.error(s"Couldn't continuously deploy $params: ${error.message}")
          }
        } catch {
          case NonFatal(e) => log.error(s"Could not deploy $params", e)
        }
      } else {
        log.info(s"Due to change freeze, continuous deployment is skipping ${params.toString}")
      }
    } else
      log.info(s"Would deploy ${params.toString}")
  }

}

object ContinuousDeployment extends Logging with Retriable {

  def getMatchesForSuccessfulBuilds(build: CIBuild, configs: Iterable[ContinuousDeploymentConfig]): Iterable[(ContinuousDeploymentConfig, CIBuild)] = {
    configs.flatMap { config =>
      log.debug(s"Matching $build against $config")
      config.findMatchOnSuccessfulBuild(build).map(build => config -> build)
    }
  }

  def getDeployParams(configBuildTuple:(ContinuousDeploymentConfig, CIBuild)): DeployParameters = {
    val (config,build) = configBuildTuple
    DeployParameters(
      Deployer("Continuous Deployment"),
      build.toMagentaBuild,
      Stage(config.stage)
    )
  }
}

