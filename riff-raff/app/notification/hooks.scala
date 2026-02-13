package notification

import java.net.{URI, URL}
import java.util.UUID

import org.apache.pekko.actor.{Actor, ActorSystem, Props}
import controllers.Logging
import lifecycle.Lifecycle
import magenta.ContextMessage._
import magenta.{DeployParameters, DeployReporter}
import magenta.Message._
import org.joda.time.DateTime
import persistence.{DataStore, DeployRecordDocument, HookConfigRepository}
import play.api.libs.json.Json
import play.api.libs.ws._

import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.util.control.NonFatal

case class Auth(
    user: String,
    password: String,
    scheme: WSAuthScheme = WSAuthScheme.BASIC
)

case class HookConfig(
    id: UUID,
    projectName: String,
    stage: String,
    url: String,
    enabled: Boolean,
    lastEdited: DateTime,
    user: String,
    method: HttpMethod = GET,
    postBody: Option[String] = None
) extends Logging {

  def request(record: DeployRecordDocument)(implicit wsClient: WSClient) = {
    val templatedUrl =
      new HookTemplate(url, record, urlEncode = true).Template.run().get
    authFor(templatedUrl)
      .map(ui =>
        wsClient.url(templatedUrl).withAuth(ui.user, ui.password, ui.scheme)
      )
      .getOrElse(wsClient.url(templatedUrl))
  }

  def authFor(url: String): Option[Auth] =
    Option(URI.create(url).toURL.getUserInfo).flatMap { ui =>
      ui.split(':') match {
        case Array(username, password) => Some(Auth(username, password))
        case _                         => None
      }
    }

  def act(
      record: DeployRecordDocument
  )(implicit wSClient: WSClient, executionContext: ExecutionContext): Unit = {
    if (enabled) {
      val urlRequest = request(record)
      log.info(s"Calling ${urlRequest.url}")
      (method match {
        case GET =>
          urlRequest.get()
        case POST =>
          postBody
            .map { t =>
              val body = new HookTemplate(t, record, urlEncode = false).Template
                .run()
                .get
              val json = Try {
                Json.parse(body)
              }.toOption
              json.map(urlRequest.post(_)).getOrElse(urlRequest.post(body))
            }
            .getOrElse(
              urlRequest.post(
                Map[String, Seq[String]](
                  "build" -> Seq(record.parameters.buildId),
                  "project" -> Seq(record.parameters.projectName),
                  "stage" -> Seq(record.parameters.stage),
                  "deployer" -> Seq(record.parameters.deployer),
                  "uuid" -> Seq(record.uuid.toString),
                  "tags" -> record.parameters.tags.toSeq.map { case (k, v) =>
                    s"$k:$v"
                  }
                )
              )
            )
      }).map { response =>
        log.info(s"HTTP status code ${response.status} from ${urlRequest.url}")
        log.debug(
          s"HTTP response body from ${urlRequest.url}: ${response.status}"
        )
      }.recover { case NonFatal(e) =>
        log.error(s"Problem calling ${urlRequest.url}", e)
      }
    } else {
      log.info("Hook disabled")
    }
  }
}
object HookConfig {
  def apply(
      projectName: String,
      stage: String,
      url: String,
      enabled: Boolean,
      updatedBy: String
  ): HookConfig =
    HookConfig(
      UUID.randomUUID(),
      projectName,
      stage,
      url,
      enabled,
      new DateTime(),
      updatedBy
    )
}

class HooksClient(
    datastore: DataStore,
    hookConfigRepository: HookConfigRepository,
    wsClient: WSClient,
    executionContext: ExecutionContext
) extends Lifecycle
    with Logging {
  lazy val system = ActorSystem("notify")
  val actor =
    try {
      Some(
        system.actorOf(
          Props(
            classOf[HooksClientActor],
            datastore,
            hookConfigRepository,
            wsClient,
            executionContext
          ),
          "hook-client"
        )
      )
    } catch {
      case t: Throwable =>
        log.error("Failed to start HookClient", t)
        None
    }

  def finishedBuild(uuid: UUID, parameters: DeployParameters): Unit = {
    actor.foreach(_ ! HooksClientActor.Finished(uuid, parameters))
  }

  val messageSub = DeployReporter.messages.subscribe(message => {
    message.stack.top match {
      case FinishContext(Deploy(parameters)) =>
        finishedBuild(message.context.deployId, parameters)
      case _ =>
    }
  })

  def init(): Unit = {}
  def shutdown(): Unit = {
    messageSub.unsubscribe()
    actor.foreach(system.stop)
  }
}

object HooksClientActor {
  trait Event
  case class Finished(uuid: UUID, params: DeployParameters)
}

class HooksClientActor(
    datastore: DataStore,
    hookConfigRepository: HookConfigRepository
)(implicit wsClient: WSClient, executionContext: ExecutionContext)
    extends Actor
    with Logging {
  import notification.HooksClientActor._

  def receive = { case Finished(uuid, params) =>
    hookConfigRepository
      .getPostDeployHook(params.build.projectName, params.stage.name)
      .foreach { config =>
        try {
          config.act(datastore.readDeploy(uuid).get)
        } catch {
          case t: Throwable =>
            log.warn(
              s"Exception caught whilst processing post deploy hooks for $config",
              t
            )
        }
      }
  }
}

object HttpMethod {
  def apply(stringRep: String): HttpMethod = stringRep match {
    case GET.serialised  => GET
    case POST.serialised => POST
    case _               =>
      throw new IllegalArgumentException(
        s"Can't translate $stringRep to HTTP verb"
      )
  }
  def all: List[HttpMethod] = List(GET, POST)
}

sealed trait HttpMethod {
  def serialised: String
  override def toString = serialised
}
case object GET extends HttpMethod {
  override val serialised = "GET"
}
case object POST extends HttpMethod {
  override val serialised = "POST"
}
