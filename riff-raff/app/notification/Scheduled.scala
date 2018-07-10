package notification

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import ci.TargetResolver
import controllers.{Logging, routes}
import lifecycle.Lifecycle
import magenta.{Deploy, DeployParameters, DeployReporter,  FailContext}

import scala.concurrent.ExecutionContext
import com.gu.anghammarad.Anghammarad
import com.gu.anghammarad.models._
import conf.Configuration
import magenta.deployment_type.DeploymentType
import magenta.input.resolver.Resolver


class Scheduled(ec: ExecutionContext, deploymentTypes: Seq[DeploymentType]) extends Lifecycle with Logging {

  private val anghammaradTopicARN = Configuration.scheduledDeployment.anghammaradTopicARN
  private val snsClient = Configuration.scheduledDeployment.snsClient
  private val prefix = Configuration.urls.publicPrefix

  def url(uuid: UUID):String = {
    val path = routes.DeployController.viewUUID(uuid.toString,true)
    prefix + path.url
  }

  def failedDeploy(uuid: UUID, parameters: DeployParameters) {
    implicit val client = Configuration.artifact.aws.client
    val bucketName = Configuration.artifact.aws.bucketName

    for {
      yaml <- TargetResolver.fetchYaml(parameters.build)
      deployGraph <- Resolver.resolveDeploymentGraph(yaml, deploymentTypes, magenta.input.All).toEither
      magentaTargets = TargetResolver.extractTargets(deployGraph)
      targets = magentaTargets.toList.flatMap{target =>
        List(App(target.app),Stack(target.stack))
      } ++ List(Stage(parameters.stage.name))
    } {
      Anghammarad.notify(
        subject = "Scheduled deploy failed",
        message = "Your scheduled deploy failed. Your applications infrastructure may become out of date. If no succesful deploys occur between now and the next scheduled attempt, it will not be retried.",
        sourceSystem = "riff-raff",
        channel = All,
        target =  targets,
        actions = List(Action("View build", url(uuid))),
        topicArn = anghammaradTopicARN,
        client = snsClient
      )(ec)
    }

  }

  val messageSub = DeployReporter.messages.subscribe(message => {
    message.stack.top match {
      case FailContext(Deploy(parameters)) =>
        failedDeploy(message.context.deployId, parameters)
      case _ =>
    }
  })

  def init() {}

  def shutdown() {
    messageSub.unsubscribe()
  }


}
