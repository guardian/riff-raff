package teamcity

import conf.Configuration.continuousDeployment
import magenta.{Stage, Build => MagentaBuild, Deployer, DeployParameters}
import controllers.{Logging, DeployController}
import akka.agent.Agent
import akka.actor.ActorSystem

object ContinuousDeployment extends Logging {

  val system = ActorSystem("continuous")
  val buildToStageMap = Agent(continuousDeployment.buildToStageMap)(system)
  buildToStageMap().foreach{ case (project, stageList) =>
    log.info("%s configured for continuous deployment to %s" format (project, stageList.mkString(", ")))
  }

  def enableAll() {
    buildToStageMap.send { continuousDeployment.buildToStageMap }
  }
  def disableAll() {
    buildToStageMap.send { _.mapValues(_ => List()) }
  }

  def enable(projectName:String,stage:String) {
    buildToStageMap.send { old =>
      val newStageList = (stage :: old(projectName)).distinct.sorted
      old + (projectName -> newStageList)
    }
  }

  def disable(projectName:String,stage:String) {
    buildToStageMap.send { old =>
      val newStageList = old(projectName).filterNot(_ == stage)
      old + (projectName -> newStageList)
    }
  }

  def status(): Map[String,List[(String,Boolean)]] = {
    val allKnown=continuousDeployment.buildToStageMap
    val allEnabled=buildToStageMap()
    allKnown.map{ case(projectName, stageList) =>
      projectName -> stageList.map{ stage => (stage, allEnabled(projectName).contains(stage)) }
    }.toMap
  }

  lazy val buildWatcher = new BuildWatcher {
    def change(previousMap: BuildTypeMap, currentMap: BuildTypeMap) {
      log.info("Checking for any new builds to deploy")
      if (!previousMap.isEmpty) {
        val previousLatestBuild = previousMap.latestBuildId()
        val relevantBuildMap = currentMap.filterKeys(buildType => buildToStageMap().keys.toList.contains(buildType.name))
        val newBuildMap = relevantBuildMap.mapValues(_.filter( _.buildId > previousLatestBuild ).headOption)
        log.info("Filtered build map %s" format newBuildMap)
        newBuildMap.foreach{ case (buildType,build) =>
          build.map{ realBuild =>
            val stages = buildToStageMap()(buildType.name)
            stages.map{ stage =>
              DeployParameters(Deployer("Continuous Deployment"), MagentaBuild(buildType.name,realBuild.number), Stage(stage))
            }.foreach{ params =>
              if (continuousDeployment.enabled) {
                log.info("Triggering deploy of %s" format params.toString)
                DeployController.deploy(params)
              } else
                log.info("Would deploy %s" format params.toString)
            }
          }
        }
      }
    }
  }

  def init() {
    TeamCity.subscribe(buildWatcher)
  }

  def shutdown() {
    TeamCity.unsubscribe(buildWatcher)
  }

}