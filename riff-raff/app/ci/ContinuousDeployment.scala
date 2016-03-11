package ci

import controllers.Logging
import deployment.Deployments
import lifecycle.LifecycleWithoutApp
import magenta.{Build => MagentaBuild, DeployParameters, Deployer, RecipeName, Stage}
import persistence.Persistence.store.getContinuousDeploymentList
import rx.lang.scala.{Observable, Subscription}
import utils.ChangeFreeze

import scala.util.control.NonFatal

object ContinuousDeployment extends LifecycleWithoutApp with Logging {

  var sub: Option[Subscription] = None

  def buildCandidates(builds: Observable[CIBuild]): Observable[CIBuild] =
    for {
      (_, buildsPerJobAndBranch) <- builds.groupBy(b => (b.jobName, b.branchName))
      build <- GreatestSoFar(buildsPerJobAndBranch.distinct)
    } yield build

  def buildsToDeploy(buildCandidates: Observable[CIBuild]): Observable[(ContinuousDeploymentConfig, CIBuild)] =
    (for {
      build <- buildCandidates
      deployable <- Observable.from(getMatchesForSuccessfulBuilds(build, getContinuousDeploymentList))
    } yield deployable)

  def init() {
    val builds = buildsToDeploy(buildCandidates(CIBuild.newBuilds)).onErrorResumeNext{ _ match {
      case NonFatal(e) =>
        log.error("Problem polling builds for ContinuousDeployment", e)
        buildsToDeploy(buildCandidates(CIBuild.newBuilds))
      }
    }

    sub = Some(builds.subscribe { x =>
      runDeploy(getDeployParams(x))
    })
  }

  def shutdown() {
    sub.foreach(_.unsubscribe())
  }

  def getDeployParams(configBuildTuple:(ContinuousDeploymentConfig, CIBuild)): DeployParameters = {
    val (config,build) = configBuildTuple
    DeployParameters(
      Deployer("Continuous Deployment"),
      MagentaBuild(build.jobName,build.number),
      Stage(config.stage),
      RecipeName(config.recipe)
    )
  }

  def runDeploy(params: DeployParameters) {
    if (conf.Configuration.continuousDeployment.enabled) {
      if (!ChangeFreeze.frozen(params.stage.name)) {
        log.info(s"Triggering deploy of ${params.toString}")
        try {
          Deployments.deploy(params)
        } catch {
          case NonFatal(e) => log.error(s"Could not deploy $params", e)
        }
      } else {
        log.info(s"Due to change freeze, continuous deployment is skipping ${params.toString}")
      }
    } else
      log.info(s"Would deploy ${params.toString}")
  }

  def getMatchesForSuccessfulBuilds(build: CIBuild, configs: Iterable[ContinuousDeploymentConfig])
    : Iterable[(ContinuousDeploymentConfig, CIBuild)] = {
    configs.flatMap { config =>
      log.debug(s"Matching $build against $config")
      config.findMatchOnSuccessfulBuild(build).map(build => config -> build)
    }
  }
}

