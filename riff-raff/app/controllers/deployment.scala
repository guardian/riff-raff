package controllers

import play.api.mvc.Controller
import play.api.data.Form
import deployment._
import play.api.data.Forms._
import conf.{TimedAction, Configuration}
import java.util.UUID
import akka.actor.ActorSystem
import magenta._
import akka.agent.Agent
import akka.util.Timeout
import akka.util.duration._
import deployment.DeployRecord
import magenta.S3Credentials
import deployment.DeployParameterForm
import magenta.KeyRing
import magenta.MessageStack
import scala.Some
import magenta.Build
import magenta.DeployParameters
import magenta.SystemUser
import magenta.Deployer
import magenta.Stage

object DeployLibrary extends Logging {
  val sink = new MessageSink {
    def message(uuid: UUID, stack: MessageStack) { update(uuid, _ + stack) }
  }
  def init() { MessageBroker.subscribe(sink) }
  def shutdown() { MessageBroker.unsubscribe(sink) }

  implicit val system = ActorSystem("deploy")

  val library = Agent(Map.empty[UUID,Agent[DeployRecord]])

  def create(params: DeployParameters, keyRing: KeyRing): UUID = {
    val uuid = java.util.UUID.randomUUID()
    library send { _ + (uuid -> Agent(DeployRecord(uuid, params, keyRing))) }
    uuid
  }

  def update(uuid:UUID, transform: DeployRecord => DeployRecord) { library()(uuid) send transform }

  def get(uuid: UUID): DeployRecord = { library()(uuid)() }

  def await(uuid: UUID): DeployRecord = {
    val timeout = Timeout(1 second)
    library.await(timeout)(uuid).await(timeout)
  }
}


object Deployment extends Controller with Logging {

  lazy val deployForm = Form[DeployParameterForm](
    mapping(
      "project" -> nonEmptyText,
      "build" -> nonEmptyText,
      "stage" -> nonEmptyText
    )(DeployParameterForm.apply)(DeployParameterForm.unapply)
  )

  def frontendArticleCode = TimedAction {
    AuthAction { request =>
      val parameters = DeployParameterForm("frontend::article","","CODE")
      Ok(views.html.frontendarticle(request, deployForm.fill(parameters)))
    }
  }

  def deploy = TimedAction {
    AuthAction { implicit request =>
      Ok(views.html.deployForm(request, deployForm))
    }
  }

  def doDeploy = TimedAction {
    AuthAction { implicit request =>
      deployForm.bindFromRequest().fold(
        errors => BadRequest(views.html.deployForm(request,errors)),
        form => {
          log.info("Form submitted")
          val deployActor = DeployActor(form.project, Stage(form.stage))
          val s3Creds = S3Credentials(Configuration.s3.accessKey,Configuration.s3.secretAccessKey)
          val keyRing = KeyRing(SystemUser(keyFile = Some(Configuration.sshKey.file)), List(s3Creds))

          val context = new DeployParameters(Deployer(request.identity.get.fullName),
            Build(form.project,form.build.toString),
            Stage(form.stage))

          val uuid = DeployLibrary.create(context, keyRing)

          import deployment.DeployActor.Deploy
          deployActor ! Deploy(uuid)

          Redirect(routes.Deployment.deployLog(uuid.toString))
        }
      )
    }
  }

  def deployLog(uuid: String, verbose:Boolean) = TimedAction {
    AuthAction { implicit request =>
      val record = DeployLibrary.get(UUID.fromString(uuid))

      Ok(views.html.deployLog(request, record, verbose))
    }
  }

  def deployLogContent(uuid: String, verbose: Boolean) = TimedAction {
    AuthAction { implicit request =>
      val record = DeployLibrary.get(UUID.fromString(uuid))

      Ok(views.html.snippets.deployLogContent(request, record, verbose))
    }
  }
}