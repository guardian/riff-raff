package teamcity

import magenta.{Build => MagentaBuild, Stage, Deployer, DeployParameters}
import controllers.{Logging, DeployController}
import akka.agent.Agent
import akka.actor.ActorSystem
import lifecycle.LifecycleWithoutApp
import com.gu.conf.{Configuration => GuConfiguration}
import deployment.{ShardingResponsibility, ShardingAction}

trait ContinuousDeploymentConfig {
  val enabled: Boolean
  val buildToStageMap: Map[String, Set[String]]
}

case class GuContinuousDeploymentConfig(config: GuConfiguration, sharding: ShardingResponsibility) extends ContinuousDeploymentConfig {
  lazy val enabled = config.getStringProperty("continuous.deployment.enabled", "false") == "true"
  lazy val configLine = config.getStringProperty("continuous.deployment", "")

  private lazy val ProjectToStageRe = """^(.+)->(.+)$""".r
  lazy val buildToStageMap = configLine.split("\\|").flatMap{ entry =>
    entry match {
      case ProjectToStageRe(project, stageList) =>
        val stages = stageList.split(",").toList
        val filteredStages = stages.filter { stage =>
          val params = DeployParameters(Deployer("n/a"), MagentaBuild(project,"n/a"), Stage(stage))
          sharding.responsibleFor(params) == ShardingAction.Local()
        }
        if (filteredStages.isEmpty)
          None
        else
          Some(project -> filteredStages.toSet)
      case _ => None
    }
  }.toMap

}

object ContinuousDeployment extends Logging with LifecycleWithoutApp {

  import conf.Configuration.continuousDeployment

  val system = ActorSystem("continuous")
  val buildToStageMap = Agent(continuousDeployment.buildToStageMap)(system)

  log.info("Continuous deployment is %s" format (if (continuousDeployment.enabled) "enabled" else "disabled"))
  buildToStageMap().foreach{ case (project, stageList) =>
    log.info("%s configured for continuous deployment to %s" format (project, stageList.mkString(", ")))
  }

  def enableAll() {
    buildToStageMap.send { continuousDeployment.buildToStageMap }
  }
  def disableAll() {
    buildToStageMap.send { _.mapValues(_ => Set()) }
  }

  def enable(projectName:String,stage:String) {
    buildToStageMap.send { old =>
      val newStageList = (old(projectName) + stage)
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
    allKnown.map{ case(projectName, stageSet) =>
      projectName -> stageSet.toList.sorted.map{ stage => (stage, allEnabled(projectName).contains(stage)) }
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