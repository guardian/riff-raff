package ci

import controllers.Logging
import deployment.Deployments
import lifecycle.LifecycleWithoutApp
import magenta.{DeployParameters, Deployer, RecipeName, Stage, Build => MagentaBuild}
import persistence.Persistence.store.getContinuousDeploymentList
import rx.lang.scala.{Observable, Subscription}
import utils.ChangeFreeze

import scala.util.{Failure, Try}
import scala.util.control.NonFatal

object ContinuousDeployment extends LifecycleWithoutApp with Logging {

  var sub: Option[Subscription] = None

  def buildCandidates(builds: Observable[CIBuild]): Observable[CIBuild] =
    (for {
      (_, buildsPerJobAndBranch) <- builds.groupBy(b => (b.jobName, b.branchName))
      build <- GreatestSoFar(buildsPerJobAndBranch.distinct)
    } yield build).onErrorResumeNext(e => {
      log.error("Problem polling builds for ContinuousDeployment", e)
      buildCandidates(CIBuild.newBuilds)
    })

  def init() {
    val builds = buildCandidates(CIBuild.newBuilds)

    val cdConfigs = retryUpTo(5)(getContinuousDeploymentList).getOrElse{
      log.error("Failed to retrieve CD configs")
      Nil
    }
    sub = Some(builds.subscribe { b =>
      getMatchesForSuccessfulBuilds(b, cdConfigs) foreach  { x =>
        runDeploy(getDeployParams(x))
      }
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

  def retryUpTo[T](maxAttempts: Int)(thunk: () => T): Try[T] = {
    val thunkStream = Stream.continually(Try(thunk()))
      .take(maxAttempts)

    thunkStream.find(_.isSuccess).getOrElse(thunkStream.head)
  }
}

