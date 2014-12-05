package ci

import controllers.{Logging, DeployController}
import lifecycle.LifecycleWithoutApp
import magenta.{Build => MagentaBuild}
import magenta.RecipeName
import magenta.DeployParameters
import magenta.Deployer
import magenta.Stage
import persistence.Persistence.store.getContinuousDeploymentList

import utils.ChangeFreeze
import rx.lang.scala.{Observable, Subscription}
import ci.teamcity.Job

object ContinuousDeployment extends LifecycleWithoutApp with Logging {
  import play.api.libs.concurrent.Execution.Implicits._

  var sub: Option[Subscription] = None

  def buildCandidates(jobs: Observable[Job], allBuilds: Job => Observable[Iterable[CIBuild]], newBuilds: Job => Observable[CIBuild])
    : Observable[CIBuild] = {
    (for {
      job <- jobs.distinct
      initial <- allBuilds(job)
      (_, buildsPerBranch) <- newBuilds(job).groupBy(_.branchName)
      build <- GreatestSoFar(buildsPerBranch).filter(!initial.toSeq.contains(_)).distinct
    } yield build).onErrorResumeNext(e => {
      log.error("Problem polling builds for ContinuousDeployment", e)
      buildCandidates(jobs, allBuilds, newBuilds)
    })
  }

  def init() {
    val builds = buildCandidates(CIBuild.jobs, TeamCityAPI.succesfulBuildBatch, CIBuild.newBuilds)

    sub = Some(builds.subscribe { b =>
      getMatchesForSuccessfulBuilds(b, getContinuousDeploymentList) foreach  { x =>
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
        DeployController.deploy(params)
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

