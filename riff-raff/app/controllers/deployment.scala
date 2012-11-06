package controllers

import teamcity._
import play.api.mvc.Controller
import play.api.data.Form
import deployment._
import deployment.Domains.responsibleFor
import deployment.DomainAction._
import play.api.data.Forms._
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
import com.codahale.jerkson.Json._
import org.joda.time.format.DateTimeFormat
import datastore.DataStore
import lifecycle.LifecycleWithoutApp

object DeployController extends Logging with LifecycleWithoutApp {
  val sink = new MessageSink {
    def message(uuid: UUID, stack: MessageStack) { update(uuid, stack) }
  }
  def init() { MessageBroker.subscribe(sink) }
  def shutdown() { MessageBroker.unsubscribe(sink) }

  implicit val system = ActorSystem("deploy")

  val library = Agent(Map.empty[UUID,Agent[DeployRecord]])

  def create(recordType: Task.Value, params: DeployParameters): DeployRecord = {
    val uuid = java.util.UUID.randomUUID()
    val record = DeployRecord(recordType, uuid, params)
    library send { _ + (uuid -> Agent(record)) }
    DataStore.createDeploy(record)
    await(uuid)
  }

  def update(uuid:UUID, stack: MessageStack) {
    library()(uuid) send { record =>
      MessageBroker.withUUID(uuid)(record + stack)
    }
    DataStore.updateDeploy(uuid, stack)
  }

  def preview(params: DeployParameters): UUID = deploy(params, Task.Preview)
  def deploy(requestedParams: DeployParameters, mode: Task.Value = Task.Deploy): UUID = {
    val params = TeamCity.transformLastSuccessful(requestedParams)
    Domains.assertResponsibleFor(params)
    val record = DeployController.create(mode, params)
    DeployControlActor.deploy(record)
    record.uuid
  }

  def getControllerDeploys: Iterable[DeployRecord] = { library().values.map{ _() } }
  def getDatastoreDeploys(limit:Int): Iterable[DeployRecord] = DataStore.getDeploys(limit)

  def getDeploys(limit:Int = 20): List[DeployRecord] = {
    val controllerDeploys = getControllerDeploys.toList
    val datastoreDeploys = getDatastoreDeploys(limit).toList
    val uuidSet = Set(controllerDeploys.map(_.uuid): _*)
    val combinedRecords = controllerDeploys ::: datastoreDeploys.filterNot(deploy => uuidSet.contains(deploy.uuid))
    combinedRecords.sortWith{ _.report.startTime.getMillis < _.report.startTime.getMillis }.takeRight(limit)
  }

  def get(uuid: UUID): DeployRecord = {
    val agent = library().get(uuid)
    agent.map(_()).getOrElse {
      DataStore.getDeploy(uuid).get
    }
  }

  def await(uuid: UUID): DeployRecord = {
    val timeout = Timeout(5 second)
    library.await(timeout)(uuid).await(timeout)
  }
}

case class DeployParameterForm(project:String, build:String, stage:String, recipe: Option[String], action: String, hosts: List[String])

object Deployment extends Controller with Logging {

  lazy val deployForm = Form[DeployParameterForm](
    tuple(
      "project" -> nonEmptyText,
      "build" -> nonEmptyText,
      "stage" -> optional(text),
      "manualStage" -> optional(text),
      "recipe" -> optional(text),
      "action" -> nonEmptyText,
      "hosts" -> list(text)
    ).verifying("no.stage.specified", _ match {
      case(_,_, stage, manualStage, _, _,_) => !stage.isEmpty || !manualStage.isEmpty}
    ).transform[DeployParameterForm]({
        case (project, build, Some(stage), _, recipe, action, hosts) =>
          DeployParameterForm(project, build, stage, recipe, action, hosts)
        case (project, build, None, Some(manualStage), recipe, action, hosts) =>
          DeployParameterForm(project, build, manualStage, recipe, action, hosts)
        case _ => throw new Error("Should have failed validation")
      },
      form => (form.project, form.build, Some(form.stage), None, None, form.action, form.hosts )
    )
  )

  def deploy = AuthAction { implicit request =>
    Ok(views.html.deploy.form(request, deployForm))
  }

  def processForm = AuthAction { implicit request =>
    deployForm.bindFromRequest().fold(
      errors => BadRequest(views.html.deploy.form(request,errors)),
      form => {
        log.info("Host list: %s" format form.hosts)
        val parameters = new DeployParameters(Deployer(request.identity.get.fullName),
          Build(form.project,form.build.toString),
          Stage(form.stage),
          recipe = form.recipe.map(RecipeName(_)).getOrElse(DefaultRecipe()),
          hostList = form.hosts)

        responsibleFor(parameters) match {
          case Local() =>
            form.action match {
              case "preview" =>
                val uuid = DeployController.preview(parameters)
                Redirect(routes.Deployment.viewUUID(uuid.toString))
              case "deploy" =>
                val uuid = DeployController.deploy(parameters)
                Redirect(routes.Deployment.viewUUID(uuid.toString))
              case _ => throw new RuntimeException("Unknown action")
            }
          case Remote(urlPrefix) =>
            val call = routes.Deployment.deployConfirmation(generate(form))
            Redirect(urlPrefix+call.url)
          case Noop() =>
            throw new IllegalArgumentException("There isn't a domain in the riff-raff configuration that can run this deploy")
        }
      }
    )
  }

  def viewUUID(uuid: String, verbose: Boolean) = AuthAction { implicit request =>
    val record = DeployController.get(UUID.fromString(uuid))
    record.taskType match {
      case Task.Deploy => Ok(views.html.deploy.log(request, record, verbose))
      case Task.Preview => Ok(views.html.deploy.preview(request,record,verbose))
    }
  }

  def updatesUUID(uuid: String) = AuthAction { implicit request =>
    val record = DeployController.get(UUID.fromString(uuid))
    record.taskType match {
      case Task.Deploy => Ok(views.html.deploy.logContent(request, record))
      case Task.Preview => Ok(views.html.deploy.previewContent(request,record))
    }
  }

  def history(count:Int) = AuthAction { implicit request =>
    Ok(views.html.deploy.history(request, count))
  }

  def historyContent(count:Int) = AuthAction { implicit request =>
    val records = DeployController.getDeploys(count).reverse
    Ok(views.html.deploy.historyContent(request, count))
  }

  def autoCompleteProject(term: String) = AuthAction {
    val possibleProjects = TeamCity.buildTypes.map(_.name).filter(_.toLowerCase.contains(term.toLowerCase)).sorted.take(10)
    Ok(Json.toJson(possibleProjects))
  }

  def autoCompleteBuild(project: String, term: String) = AuthAction {
    val possibleProjects = TeamCity.successfulBuilds(project).filter(p => p.number.contains(term) || p.branch.contains(term)).map { build =>
      val formatter = DateTimeFormat.forPattern("HH:mm d/M/yy")
      val label = "%s [%s] (%s)" format (build.number, build.branch, formatter.print(build.startDate))
      Map("label" -> label, "value" -> build.number)
    }
    Ok(Json.toJson(possibleProjects))
  }

  def teamcity = AuthAction {
    val header = Seq("Build Type Name", "Build Number", "Build Branch", "Build Type ID", "Build ID")
    val data =
      for((buildType, builds) <- TeamCity.buildMap;
          build <- builds)
        yield Seq(buildType.name,build.number,build.branch,buildType.id,build.buildId)

    Ok((header :: data.toList).map(_.mkString(",")).mkString("\n")).as("text/csv")
  }

  def continuousState() = AuthAction { implicit request =>
    case class ContinuousParameters(projectName:String, stage:String, enabled:Boolean)
    lazy val continuousForm = Form[ContinuousParameters](
      mapping(
        "projectName" -> nonEmptyText,
        "stage" -> nonEmptyText,
        "enabled" -> boolean
      )(ContinuousParameters.apply)
        (ContinuousParameters.unapply)
    )

    continuousForm.bindFromRequest().fold(
      errors => Redirect(routes.Deployment.continuousDeployment()),
      form => {
        log.info("request: %s" format form)
        if (form.enabled)
          ContinuousDeployment.enable(form.projectName,form.stage)
        else
          ContinuousDeployment.disable(form.projectName,form.stage)
      }
    )
    Redirect(routes.Deployment.continuousDeployment())
  }

  def continuousStateGlobal() = AuthAction { implicit request =>
    case class ContinuousParameters(enabled:Boolean)
    lazy val continuousForm = Form[ContinuousParameters](
      mapping(
        "enabled" -> boolean
      )(ContinuousParameters.apply)
        (ContinuousParameters.unapply)
    )

    continuousForm.bindFromRequest().fold(
      errors => Redirect(routes.Deployment.continuousDeployment()),
      form => {
        log.info("request: %s" format form)
        if (form.enabled)
          ContinuousDeployment.enableAll()
        else
          ContinuousDeployment.disableAll()
      }
    )
    Redirect(routes.Deployment.continuousDeployment())
  }

  def continuousDeployment() = AuthAction { implicit request =>
    val status = ContinuousDeployment.status()
    Ok(views.html.deploy.continuousDeployment(request, status))
  }

  def deployConfirmation(deployFormJson: String) = AuthAction { implicit request =>
    val parametersJson = Json.parse(deployFormJson)
    Ok(views.html.deploy.deployConfirmation(request, deployForm.bind(parametersJson)))
  }

}