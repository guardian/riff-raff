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
import deployment.DomainAction.Local
import deployment.Domains
import org.joda.time.DateTime
import teamcity.Build
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.commons.Implicits._
import akka.agent.Agent
import akka.actor.ActorSystem

object Trigger extends Enumeration {
  type Mode = Value
  val SuccessfulBuild = Value(1, "Successful build")
  val BuildTagged = Value(2, "Build tagged")
  val Disabled = Value(0, "Disabled")
}

case class ContinuousDeploymentConfig(
  id: UUID,
  projectName: String,
  stage: String,
  recipe: String,
  branchMatcher:Option[String],
  trigger: Trigger.Mode,
  tag: Option[String],
  user: String,
  lastEdited: DateTime = new DateTime()
) {
  lazy val branchRE = branchMatcher.map(re => "^%s$".format(re).r).getOrElse(".*".r)
  lazy val buildFilter =
    (build:Build) => build.buildType.fullName == projectName && branchRE.findFirstMatchIn(build.branchName).isDefined

  def findMatchOnSuccessfulBuild(builds: List[Build]): Option[Build] = {
    if (trigger == Trigger.SuccessfulBuild) {
      builds.filter(buildFilter).sortBy(-_.id).find { build =>
        val olderBuilds = TeamCityBuilds.successfulBuilds(projectName).filter(buildFilter)
        !olderBuilds.exists(_.id > build.id)
      }
    } else None
  }
  def findMatchOnBuildTagged(builds: List[Build], newTag: String): Option[Build] = {
    if (trigger == Trigger.BuildTagged && tag.get == newTag) {
      builds.filter(buildFilter).sortBy(-_.id).headOption
    } else None
  }
  lazy val enabled = trigger != Trigger.Disabled
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
        (a.branchMatcher map ("branchMatcher" -> _)) ++
        (a.tag map ("tag" -> _))
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
        tag = dbo.getAs[String]("tag"),
        user = dbo.as[String]("user"),
        lastEdited = dbo.as[DateTime]("lastEdited"),
        branchMatcher = dbo.getAs[String]("branchMatcher")
      ))

    }
  }
}

object ContinuousDeployment extends LifecycleWithoutApp with Logging {

  val system = ActorSystem("continuous-deployment")
  var buildWatcher: Option[ContinuousDeployment] = None
  val tagWatcherAgent = Agent[Map[String, TagWatcher]](Map.empty)(system)

  def init() {
    if (buildWatcher.isEmpty) {
      buildWatcher = Some(new ContinuousDeployment(Domains))
      buildWatcher.foreach(TeamCityBuilds.subscribe)
    }
    updateTagTrackers()
  }

  def updateTagTrackers() {
    val configuredTags = Persistence.store.getContinuousDeploymentList.filter(_.trigger == Trigger.BuildTagged).flatMap(_.tag).toSet
    syncTagTrackers(configuredTags)
  }
  def syncTagTrackers(targetTags: Set[String]) {
    tagWatcherAgent.send{ tagWatchers =>
      log.info("Updating trackers")
      log.debug(s"Desired tags: $targetTags")
      val running = tagWatchers.keys.toSet
      log.debug(s"Running trackers: $running")
      val deleted = running -- targetTags
      deleted.foreach{ toRemove =>
        TeamCityBuilds.unsubscribe(tagWatchers(toRemove))
      }
      log.debug(s"Deleted trackers: $deleted")
      val required = targetTags -- running
      log.debug(s"Required trackers: $required")
      val newTrackers = required.map { toCreate =>
        val watcher = new TagWatcher {
          val tag = toCreate
          def newBuilds(builds: List[Build]) {
            buildWatcher.foreach(_.newTags(builds, toCreate))
          }
        }
        TeamCityBuilds.subscribe(watcher)
        toCreate -> watcher
      }
      tagWatchers -- deleted ++ newTrackers
    }
  }

  def shutdown() {
    buildWatcher.foreach(TeamCityBuilds.unsubscribe)
    buildWatcher = None
    syncTagTrackers(Set.empty)
  }
}

class ContinuousDeployment(domains: Domains) extends BuildWatcher with Logging {

  type ProjectCdMap = Map[String, Set[ContinuousDeploymentConfig]]

  def getMatchesForSuccessfulBuilds(builds: List[Build],
                                    configs: Iterable[ContinuousDeploymentConfig]): Iterable[(ContinuousDeploymentConfig, Build)] = {
    configs.flatMap { config =>
      config.findMatchOnSuccessfulBuild(builds).map(build => config -> build)
    }
  }

  def getMatchesForBuildTagged(builds: List[Build],
                               tag: String,
                               configs: Iterable[ContinuousDeploymentConfig]): Iterable[(ContinuousDeploymentConfig, Build)] = {
    configs.flatMap { config =>
      config.findMatchOnBuildTagged(builds, tag).map(build => config -> build)
    }
  }

  def getDeployParams(configBuildTuple:(ContinuousDeploymentConfig, Build)): Option[DeployParameters] = {
    val (config,build) = configBuildTuple
    val params = DeployParameters(
      Deployer("Continuous Deployment"),
      MagentaBuild(build.buildType.fullName,build.number),
      Stage(config.stage),
      RecipeName(config.recipe)
    )
    domains.responsibleFor(params) match {
      case Local() => Some(params)
      case _ => None
    }
  }

  def runDeploy(params: DeployParameters) {
    if (conf.Configuration.continuousDeployment.enabled) {
      log.info("Triggering deploy of %s" format params.toString)
      DeployController.deploy(params)
    } else
      log.info("Would deploy %s" format params.toString)
  }

  def newBuilds(newBuilds: List[Build]) = {
    log.info(s"New builds to consider for deployment $newBuilds")
    getMatchesForSuccessfulBuilds(newBuilds, getContinuousDeploymentList).flatMap{ getDeployParams }.foreach(runDeploy)
  }

  def newTags(newBuilds: List[Build], tag: String) = {
    log.info(s"Builds just tagged with $tag to consider for deployment: $newBuilds")
    getMatchesForBuildTagged(newBuilds, tag, getContinuousDeploymentList).flatMap{ getDeployParams }.foreach(runDeploy)
  }

}