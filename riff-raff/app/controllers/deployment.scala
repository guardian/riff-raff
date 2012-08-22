package controllers

import teamcity.TeamCity
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
import magenta.MessageStack
import magenta.Build
import magenta.DeployParameters
import magenta.Deployer
import magenta.Stage
import play.api.libs.json.Json
import org.joda.time.format.DateTimeFormat

object DeployController extends Logging {
  val sink = new MessageSink {
    def message(uuid: UUID, stack: MessageStack) { update(uuid){_ + stack} }
  }
  def init() { MessageBroker.subscribe(sink) }
  def shutdown() { MessageBroker.unsubscribe(sink) }

  implicit val system = ActorSystem("deploy")

  val library = Agent(Map.empty[UUID,Agent[DeployRecord]])

  def create(recordType: Task.Type, params: DeployParameters): UUID = {
    val uuid = java.util.UUID.randomUUID()
    library send { _ + (uuid -> Agent(DeployRecord(recordType, uuid, params, DeployInfoManager.deployInfo))) }
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

  def preview(params: DeployParameters): UUID = {
    import deployment.DeployActor.Resolve
    val uuid = DeployController.create(Task.Preview, params)
    DeployActor(params.build.projectName,params.stage) ! Resolve(uuid)
    uuid
  }

  def deploy(params: DeployParameters): UUID = {
    import deployment.DeployActor.Execute
    val uuid = DeployController.create(Task.Deploy, params)
    DeployActor(params.build.projectName,params.stage) ! Execute(uuid)
    uuid
  }

  def get: List[DeployRecord] = { library().values.map{ _() }.toList.sortWith{ _.report.startTime.getMillis < _.report.startTime.getMillis } }

  def get(uuid: UUID): DeployRecord = { library()(uuid)() }

  def await(uuid: UUID): DeployRecord = {
    val timeout = Timeout(1 second)
    library.await(timeout)(uuid).await(timeout)
  }
}

case class DeployParameterForm(project:String, build:String, stage:String, action: String, hosts: List[String])

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
              val uuid = DeployController.preview(context)
              Redirect(routes.Deployment.previewLog(uuid.toString))
            case "deploy" =>
              val uuid = DeployController.deploy(context)
              Redirect(routes.Deployment.deployLog(uuid.toString))
            case _ => throw new RuntimeException("Unknown action")
          }
        }
      )
    }
  }

  def viewUUID(uuid: String) = TimedAction {
    AuthAction { implicit request =>
      val record = DeployController.get(UUID.fromString(uuid))
      record.taskType match {
        case Task.Deploy => Redirect(routes.Deployment.deployLog(uuid))
        case Task.Preview => Redirect(routes.Deployment.previewLog(uuid))
      }
    }
  }

  def previewLog(uuid: String, verbose: Boolean) = TimedAction {
    AuthAction { implicit request =>
      val record = DeployController.get(UUID.fromString(uuid))
      Ok(views.html.deploy.preview(request,record,verbose))
    }
  }

  def previewContent(uuid: String) = TimedAction {
    AuthAction { implicit request =>
      val record = DeployController.get(UUID.fromString(uuid))
      Ok(views.html.deploy.previewContent(request,record))
    }
  }

  def deployLog(uuid: String, verbose:Boolean) = TimedAction {
    AuthAction { implicit request =>
      val record = DeployController.get(UUID.fromString(uuid))

      Ok(views.html.deploy.log(request, record, verbose))
    }
  }

  def deployLogContent(uuid: String) = TimedAction {
    AuthAction { implicit request =>
      val record = DeployController.get(UUID.fromString(uuid))

      Ok(views.html.deploy.logContent(request, record))
    }
  }

  def history() = TimedAction {
    AuthAction { implicit request =>
      val records = DeployController.get.reverse

      Ok(views.html.deploy.history(request, records))
    }
  }

  def autoCompleteProject(term: String) = AuthAction {
    val possibleProjects = TeamCity.buildTypes.map(_.name).filter(_.toLowerCase.contains(term.toLowerCase)).sorted.take(10)
    Ok(Json.toJson(possibleProjects))
  }

  def autoCompleteBuild(project: String, term: String) = AuthAction {
    val possibleProjects = TeamCity.successfulBuilds(project).filter(_.number.contains(term)).map { build =>
      val formatter = DateTimeFormat.forPattern("HH:mm d MMMM yyyy")
      val label = "%s (%s)" format (build.number, formatter.print(build.startDate))
      Map("label" -> label, "value" -> build.number)
    }
    Ok(Json.toJson(possibleProjects))
  }

  def teamcity = AuthAction {
    val header = Seq("Build Type Name", "Build Number", "Build Type ID", "Build ID")
    val data =
      for((buildType, builds) <- TeamCity.buildMap;
          build <- builds)
        yield Seq(buildType.name,build.number,buildType.id,build.buildId)

    Ok((header :: data.toList).map(_.mkString(",")).mkString("\n")).as("text/csv")
  }
}