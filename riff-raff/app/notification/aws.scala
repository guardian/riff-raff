package notification

import magenta._
import java.util.UUID
import magenta.FailContext
import magenta.Deploy
import magenta.FinishContext
import magenta.StartContext
import controllers.{routes, Logging}
import conf.Configuration
import magenta.DeployParameters
import play.api.libs.json._
import lifecycle.LifecycleWithoutApp
import org.joda.time.DateTime
import utils.Json.DefaultJodaDateWrites
import com.amazonaws.auth.BasicAWSCredentials
import conf.Configuration.notifications.aws
import com.amazonaws.services.sns.AmazonSNSAsyncClient
import com.amazonaws.services.sns.model.{PublishResult, PublishRequest}
import com.amazonaws.handlers.AsyncHandler

/*
 Send deploy events to alerta (and graphite)
 */

object AWS extends Logging with LifecycleWithoutApp {
  val snsClient = (aws.accessKey, aws.secretKey, aws.topicName) match {
    case (Some(accessKey), Some(secretKey), Some(topicName), Some(region)) => {
      val creds = new BasicAWSCredentials(accessKey, secretKey)
      Some(new AmazonSNSAsyncClient(creds))
    }
    case _ => None
  }

  def sendMessage(event: JsValue) {
    snsClient.foreach { client =>
      val request = new PublishRequest(aws.topicName.get, event.toString())
      log.info(s"Queuing event to send to AWS $event")
      client.publishAsync(request, new AsyncHandler[PublishRequest,PublishResult] {
        def onSuccess(request: PublishRequest, result: PublishResult) {
          log.info(s"Successfully published message to AWS topic: $result")
        }
        def onError(exception: Exception) {
          log.warn(s"Error writing to AWS topic", exception)
        }
      })
    }
  }

  lazy val sink = new MessageSink {
    def message(message: MessageWrapper) {
      val uuid = message.context.deployId
        message.stack.top match {
          case StartContext(Deploy(parameters)) =>
            sendMessage(AWSEvent(DeployEvent.Start, uuid, parameters, message.stack.time))
          case FailContext(Deploy(parameters)) =>
            sendMessage(AWSEvent(DeployEvent.Fail, uuid, parameters, message.stack.time))
          case FinishContext(Deploy(parameters)) =>
            sendMessage(AWSEvent(DeployEvent.Complete, uuid, parameters, message.stack.time))
          case _ =>
        }
    }
  }

  def init() {
    MessageBroker.subscribe(sink)
  }

  def shutdown() {
    MessageBroker.unsubscribe(sink)
  }
}

object AWSEvent {
  def apply(event:DeployEvent.Value, uuid:UUID, params:DeployParameters, timestamp: DateTime): JsValue  = {
    val project = params.build.projectName

    val adjectiveMap = Map( DeployEvent.Complete -> "completed", DeployEvent.Fail -> "failed", DeployEvent.Start -> "started")

    Json.obj(
      "id" -> uuid.toString,
      "project" -> project,
      "build" -> params.build.id,
      "stage" -> params.stage.name,
      "recipe" -> params.recipe.name,
      "deployer" -> params.deployer.name,
      "hostList" -> params.hostList,
      "text" -> s"Deploy of $project ${adjectiveMap(event)}",
      "event" -> event.toString,
      "adjective" -> adjectiveMap(event),
      "href" -> s"${Configuration.urls.publicPrefix}${routes.Deployment.viewUUID(uuid.toString).url}",
      "createTime" -> timestamp.toDateTime
    )
  }
}
