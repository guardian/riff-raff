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
import persistence.{MongoFormat, MongoSerialisable, Persistence}
import persistence.Persistence.store.getContinuousDeploymentList
import org.joda.time.DateTime
import teamcity.TeamcityBuild
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.commons.Implicits._
import akka.agent.Agent
import akka.actor.ActorSystem
import utils.ChangeFreeze
import rx.lang.scala.{Subscription, Observable}

object Trigger extends Enumeration {
  type Mode = Value
  val SuccessfulBuild = Value(1, "Successful build")
  val Disabled = Value(0, "Disabled")
}

case class ContinuousDeploymentConfig(
  id: UUID,
  projectName: String,
  stage: String,
  recipe: String,
  branchMatcher:Option[String],
  trigger: Trigger.Mode,
  user: String,
  lastEdited: DateTime = new DateTime()
) {
  lazy val branchRE = branchMatcher.map(re => "^%s$".format(re).r).getOrElse(".*".r)
  lazy val buildFilter =
    (build:CIBuild) => build.projectName == projectName && branchRE.findFirstMatchIn(build.branchName).isDefined

  def findMatchOnSuccessfulBuild(builds: List[CIBuild]): Option[CIBuild] = {
    if (trigger == Trigger.SuccessfulBuild) {
      builds.filter(buildFilter).sortBy(-_.id).find { build =>
        val olderBuilds = TeamCityBuilds.successfulBuilds(projectName).filter(buildFilter)
        !olderBuilds.exists(_.id > build.id)
      }
    } else None
  }
}

object ContinuousDeploymentConfig extends MongoSerialisable[ContinuousDeploymentConfig] {
  implicit val configFormat: MongoFormat[ContinuousDeploymentConfig] = new ConfigMongoFormat
  private class ConfigMongoFormat extends MongoFormat[ContinuousDeploymentConfig] {
    def toDBO(a: ContinuousDeploymentConfig) = {
      val values = Map(
        "_id" -> a.id,
        "projectName" -> a.projectName,
        "stage" -> a.stage,
        "recipe" -> a.recipe,
        "triggerMode" -> a.trigger.id,
        "user" -> a.user,
        "lastEdited" -> a.lastEdited
      ) ++
        (a.branchMatcher map ("branchMatcher" -> _))
      values.toMap
    }
    def fromDBO(dbo: MongoDBObject) = {
      val enabledDB = dbo.getAs[Boolean]("enabled")
      val triggerDB = dbo.getAs[Int]("triggerMode")
      val triggerMode = (enabledDB, triggerDB) match {
        case (_, Some(triggerModeId)) => Trigger(triggerModeId)
        case (Some(true), None) => Trigger.SuccessfulBuild
        case (Some(false), None) => Trigger.Disabled
      }

      Some(ContinuousDeploymentConfig(
        id = dbo.as[UUID]("_id"),
        projectName = dbo.as[String]("projectName"),
        stage = dbo.as[String]("stage"),
        recipe = dbo.as[String]("recipe"),
        trigger = triggerMode,
        user = dbo.as[String]("user"),
        lastEdited = dbo.as[DateTime]("lastEdited"),
        branchMatcher = dbo.getAs[String]("branchMatcher")
      ))

    }
  }
}

object ReactiveDeployment extends LifecycleWithoutApp with Logging {
  import play.api.libs.concurrent.Execution.Implicits._

  var sub: Option[Subscription] = None

  def init() {
    val builds = NotFirstBatch(Unseen(Builds.teamcity))
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
      MagentaBuild(build.projectName,build.number),
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

  def getMatchesForSuccessfulBuilds(build: Iterable[CIBuild], configs: Iterable[ContinuousDeploymentConfig])
    : Iterable[(ContinuousDeploymentConfig, CIBuild)] = {
    configs.flatMap { config =>
      config.findMatchOnSuccessfulBuild(build.toList).map(build => config -> build)
    }
  }
}

trait CIBuild {
  def projectName: String
  def branchName: String
  def number: String
  def id: Long
}