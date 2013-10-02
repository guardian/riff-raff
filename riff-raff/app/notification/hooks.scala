package notification

import controllers.{DeployController, Logging}
import magenta._
import akka.actor.{Actor, Props, ActorSystem}
import lifecycle.LifecycleWithoutApp
import persistence.{DeployRecordDocument, MongoFormat, MongoSerialisable, Persistence}
import deployment.TaskType
import java.net.{URLEncoder, URL}
import com.mongodb.casbah.commons.{Imports, MongoDBObject}
import magenta.FinishContext
import magenta.DeployParameters
import magenta.Deploy
import scala.Some
import play.api.libs.ws.WS
import com.ning.http.client.Realm.AuthScheme
import play.api.libs.concurrent.Execution.Implicits._
import java.util.UUID
import org.joda.time.DateTime

case class Auth(user:String, password:String, scheme:AuthScheme=AuthScheme.BASIC)

case class HookConfig(id: UUID,
                      projectName: String,
                      stage: String,
                      url: String,
                      enabled: Boolean,
                      lastEdited: DateTime,
                      user:String) extends Logging {

  val substitutionPoint = """%deploy\.([A-Za-z]+\.?)([A-Za-z-_]+)?%""".r
  def request(record: DeployRecordDocument) = {
    val newUrl = substitutionPoint.replaceAllIn(url, (substitution) => {
      URLEncoder.encode(substitution.group(1).toLowerCase match {
        case "build" => record.parameters.buildId
        case "project" => record.parameters.projectName
        case "stage" => record.parameters.stage
        case "recipe" => record.parameters.recipe
        case "hosts" => record.parameters.hostList.mkString(",")
        case "deployer" => record.parameters.deployer
        case "uuid" => record.uuid.toString
        case "tag." => Option(substitution.group(2)).flatMap(record.parameters.tags.get).getOrElse("")
      }, "utf-8")
    })
    val userInfo = Option(new URL(newUrl).getUserInfo).flatMap { ui =>
      val elements = ui.split(':')
      if (elements.length == 2)
        Some(Auth(elements(0), elements(1)))
      else
        None
    }
    userInfo.map(ui => WS.url(newUrl).withAuth(ui.user, ui.password, ui.scheme)).getOrElse(WS.url(newUrl))
  }

  def act(record: DeployRecordDocument) {
    if (enabled) {
      val urlRequest = request(record)
      log.info(s"Calling ${urlRequest.url}")
      urlRequest.get().map { response =>
        log.info(s"HTTP status code ${response.status} from ${urlRequest.url}")
        log.debug(s"HTTP response body from ${urlRequest.url}: ${response.status}")
      }
    } else {
      log.info("Hook disabled")
    }
  }
}
object HookConfig extends MongoSerialisable[HookConfig] {
  def apply(projectName: String, stage: String, url: String, enabled: Boolean, updatedBy:String): HookConfig =
    HookConfig(UUID.randomUUID(), projectName, stage, url, enabled, new DateTime(), updatedBy)
  implicit val hookFormat: MongoFormat[HookConfig] = new HookMongoFormat
  private class HookMongoFormat extends MongoFormat[HookConfig] {
    def toDBO(a: HookConfig) = MongoDBObject(
      "_id" -> a.id,
      "projectName" -> a.projectName,
      "stage" -> a.stage,
      "url" -> a.url,
      "enabled" -> a.enabled,
      "lastEdited" -> a.lastEdited,
      "user" -> a.user
    )
    def fromDBO(dbo: MongoDBObject) = Some(HookConfig(
      dbo.as[UUID]("_id"),
      dbo.as[String]("projectName"),
      dbo.as[String]("stage"),
      dbo.as[String]("url"),
      dbo.as[Boolean]("enabled"),
      dbo.as[DateTime]("lastEdited"),
      dbo.as[String]("user")
    ))
  }
}

case class HookCriteria(projectName: String, stage: String)
object HookCriteria extends MongoSerialisable[HookCriteria] {
  def apply(parameters:DeployParameters): HookCriteria = HookCriteria(parameters.build.projectName, parameters.stage.name)
  implicit val criteriaFormat: MongoFormat[HookCriteria] =
    new CriteriaMongoFormat
  private class CriteriaMongoFormat extends MongoFormat[HookCriteria] {
    def toDBO(a: HookCriteria) = MongoDBObject("projectName" -> a.projectName, "stageName" -> a.stage)
    def fromDBO(dbo: MongoDBObject) = Some(HookCriteria(dbo.as[String]("projectName"), dbo.as[String]("stageName")))
  }
}

case class HookAction(url: String, enabled: Boolean) extends Logging with MongoSerialisable[HookAction]
object HookAction extends MongoSerialisable[HookAction] {
  implicit val actionFormat: MongoFormat[HookAction] = new ActionMongoFormat
  private class ActionMongoFormat extends MongoFormat[HookAction] {
    def toDBO(a: HookAction) = MongoDBObject("url" -> a.url, "enabled" -> a.enabled)
    def fromDBO(dbo: MongoDBObject) = Some(HookAction(dbo.as[String]("url"), dbo.as[Boolean]("enabled")))
  }
}

object HooksClient extends LifecycleWithoutApp with Logging {
  trait Event
  case class Finished(uuid: UUID, params: DeployParameters)

  lazy val system = ActorSystem("notify")
  val actor = try {
    Some(system.actorOf(Props[HooksClient], "hook-client"))
  } catch { case t:Throwable => None }

  def finishedBuild(uuid: UUID, parameters: DeployParameters) {
    actor.foreach(_ ! Finished(uuid, parameters))
  }

  val sink = new MessageSink {
    def message(message: MessageWrapper) {
      message.stack.top match {
        case FinishContext(Deploy(parameters)) =>
          if (DeployController.get(message.context.deployId).taskType == TaskType.Deploy)
            finishedBuild(message.context.deployId, parameters)
        case _ =>
      }
    }
  }

  def doMigration() {
    val MIGRATED_KEY = "migratedToHookConfig"
    if (Persistence.store.readKey(MIGRATED_KEY).isEmpty) {
      log.info("Migrating deploy hooks to V2")
      val oldHooks = Persistence.store.getPostDeployHooks
      val newHooks = Persistence.store.getPostDeployHookList
      assert(newHooks.isEmpty, "New hooks collection not empty")
      log.info(s"Migrating ${oldHooks.size} hooks")
      oldHooks.foreach{ case (criteria, action) =>
        val config = HookConfig(criteria.projectName, criteria.stage, action.url, action.enabled, "Migration")
        log.info(s"Migrating criteria $criteria and action $action as config $config")
        Persistence.store.setPostDeployHook(config)
      }
      val migratedHooks = Persistence.store.getPostDeployHookList
      log.info(s"Found ${migratedHooks.size} migrated hooks")
      assert(oldHooks.size == migratedHooks.size, "Migration failed")
      Persistence.store.writeKey(MIGRATED_KEY, "true")
      log.info("Migration of deploy hooks to V2 completed successfully!")
    }
  }

  def init() {
    doMigration()
    MessageBroker.subscribe(sink)
  }

  def shutdown() {
    MessageBroker.unsubscribe(sink)
    actor.foreach(system.stop)
  }
}

class HooksClient extends Actor with Logging {
  import HooksClient._

  def receive = {
    case Finished(uuid, params) =>
      Persistence.store.getPostDeployHook(params.build.projectName, params.stage.name).foreach { config =>
        try {
          config.act(Persistence.store.readDeploy(uuid).get)
        } catch {
          case t:Throwable =>
            log.warn(s"Exception caught whilst processing post deploy hooks for $config", t)
        }
      }
  }
}