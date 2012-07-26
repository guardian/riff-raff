package controllers

import play.api.mvc.Controller
import play.api.data.Form
import deployment._
import play.api.data.Forms._
import conf.{TimedAction, Configuration}
import java.util.UUID
import akka.actor.ActorSystem
import deployment.DeployRecord
import magenta.S3Credentials
import deployment.DeploymentKey
import deployment.DeployParameterForm
import magenta.KeyRing
import scala.Some
import magenta.Build
import magenta.DeployParameters
import magenta.SystemUser
import magenta.Deployer
import magenta.Stage
import akka.agent.Agent

object DeployLibrary extends Logging {
  implicit val system = ActorSystem("deploy")

  val library = Agent(Map.empty[UUID,Agent[DeployRecord]])

  def create(params: DeployParameters): UUID = {
    val uuid = java.util.UUID.randomUUID()
    library send { _ + (uuid -> Agent(DeployRecord(uuid, params))) }
    uuid
  }

  def update(uuid:UUID, transform: DeployRecord => DeployRecord) { library()(uuid) send transform }

  def get(uuid: UUID): DeployRecord = { library()(uuid)() }
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

          val key = DeploymentKey(form.stage,form.project,form.build)
          MessageBus.clear(key)

          import deployment.DeployActor.Deploy
          deployActor ! Deploy(context, keyRing)

          Redirect(routes.Deployment.deployLog(key.stage,key.project,key.build))
        }
      )
    }
  }

  def deployLog(stage: String, project: String, build: String, recipe: String = "default", verbose:Boolean) = TimedAction {
    AuthAction { implicit request =>
      val key = DeploymentKey(stage,project,build,recipe)
      val report = MessageBus.deployReport(key)

      Ok(views.html.deployLog(request, key, report,verbose))
    }
  }

  def deployLogContent(stage: String, project: String, build: String, recipe: String, verbose: Boolean) = TimedAction {
    AuthAction { implicit request =>
      val key = DeploymentKey(stage,project,build,recipe)
      val report = MessageBus.deployReport(key)

      Ok(views.html.snippets.deployLogContent(request,report,verbose))
    }
  }
}