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
    def message(uuid: UUID, stack: MessageStack) { update(uuid){_ + stack} }
  }
  def init() { MessageBroker.subscribe(sink) }
  def shutdown() { MessageBroker.unsubscribe(sink) }

  implicit val system = ActorSystem("deploy")

  val library = Agent(Map.empty[UUID,Agent[DeployRecord]])

  def create(recordType: Task.Type, params: DeployParameters, keyRing: KeyRing): UUID = {
    val uuid = java.util.UUID.randomUUID()
    library send { _ + (uuid -> Agent(DeployRecord(recordType, uuid, params, keyRing))) }
    uuid
  }

  def update(uuid:UUID)(transform: DeployRecord => DeployRecord) {
    library()(uuid) send { record =>
      MessageBroker.withUUID(uuid)(transform(record))
    }
  }

  def updateWithContext()(transform: DeployRecord => DeployRecord) {
    val mainThreadContext = MessageBroker.peekContext()
    val uuid = mainThreadContext._1
    library()(uuid) send { record =>
      MessageBroker.pushContext(mainThreadContext)(transform(record))
    }
  }

  def get(): List[DeployRecord] = { library().values.map{ _() }.toList }

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
      "stage" -> nonEmptyText,
      "action" -> nonEmptyText,
      "hosts" -> list(text)
    )(DeployParameterForm.apply)
     (DeployParameterForm.unapply)
  )

  def frontendArticleCode = TimedAction {
    AuthAction { request =>
      val parameters = DeployParameterForm("frontend::article","","CODE","",Nil)
      Ok(views.html.frontendarticle(request, deployForm.fill(parameters)))
    }
  }

  def deploy = TimedAction {
    AuthAction { implicit request =>
      Ok(views.html.deploy.form(request, deployForm))
    }
  }

  def processForm = TimedAction {
    AuthAction { implicit request =>
      deployForm.bindFromRequest().fold(
        errors => BadRequest(views.html.deploy.form(request,errors)),
        form => {
          log.info("Form submitted")
          val deployActor = DeployActor(form.project, Stage(form.stage))
          val s3Creds = S3Credentials(Configuration.s3.accessKey,Configuration.s3.secretAccessKey)
          val keyRing = KeyRing(SystemUser(keyFile = Some(Configuration.sshKey.file)), List(s3Creds))

          log.info("Host list: %s" format form.hosts)
          val context = new DeployParameters(Deployer(request.identity.get.fullName),
            Build(form.project,form.build.toString),
            Stage(form.stage),
            hostList = form.hosts)

          // distinguish between preview and go do it here
          import deployment.DeployActor.Resolve
          import deployment.DeployActor.Execute
          form.action match {
            case "preview" =>
              val uuid = DeployLibrary.create(Task.Preview, context, keyRing)
              deployActor ! Resolve(uuid)
              Redirect(routes.Deployment.previewLog(uuid.toString))
            case "deploy" =>
              val uuid = DeployLibrary.create(Task.Deploy, context, keyRing)
              deployActor ! Execute(uuid)
              Redirect(routes.Deployment.deployLog(uuid.toString))
            case _ => throw new RuntimeException("Unknown action")
          }
        }
      )
    }
  }

  def viewUUID(uuid: String) = TimedAction {
    AuthAction { implicit request =>
      val record = DeployLibrary.get(UUID.fromString(uuid))
      record.taskType match {
        case Task.Deploy => Redirect(routes.Deployment.deployLog(uuid))
        case Task.Preview => Redirect(routes.Deployment.previewLog(uuid))
      }
    }
  }

  def previewLog(uuid: String, verbose: Boolean) = TimedAction {
    AuthAction { implicit request =>
      val record = DeployLibrary.get(UUID.fromString(uuid))
      Ok(views.html.deploy.preview(request,record,verbose))
    }
  }

  def previewContent(uuid: String) = TimedAction {
    AuthAction { implicit request =>
      val record = DeployLibrary.get(UUID.fromString(uuid))
      Ok(views.html.deploy.previewContent(request,record))
    }
  }

  def deployLog(uuid: String, verbose:Boolean) = TimedAction {
    AuthAction { implicit request =>
      val record = DeployLibrary.get(UUID.fromString(uuid))

      Ok(views.html.deploy.log(request, record, verbose))
    }
  }

  def deployLogContent(uuid: String) = TimedAction {
    AuthAction { implicit request =>
      val record = DeployLibrary.get(UUID.fromString(uuid))

      Ok(views.html.deploy.logContent(request, record))
    }
  }

  def history() = TimedAction {
    AuthAction { implicit request =>
      val records = DeployLibrary.get()

      Ok(views.html.deploy.history(request, records))
    }
  }
}