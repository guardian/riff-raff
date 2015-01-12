package controllers

import java.util.UUID

import ci.{TagClassification, TeamCityAPI, TeamCityBuilds}
import deployment.{Deployments, PreviewController, PreviewResult}
import magenta._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.Json
import play.api.mvc.Controller
import resources.LookupSelector

import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future
import scala.util.{Failure, Success}

case class DeployParameterForm(project:String, build:String, stage:String, recipe: Option[String], action: String, hosts: List[String], stacks: List[String])
case class UuidForm(uuid:String, action:String)

object DeployController extends Controller with Logging with LoginActions {

  lazy val uuidForm = Form[UuidForm](
    mapping(
      "uuid" -> text(36, 36),
      "action" -> nonEmptyText
    )(UuidForm.apply)
      (UuidForm.unapply)
  )

  lazy val deployForm = Form[DeployParameterForm](
    mapping(
      "project" -> nonEmptyText,
      "build" -> nonEmptyText,
      "stage" -> text,
      "recipe" -> optional(text),
      "action" -> nonEmptyText,
      "hosts" -> list(text),
      "stacks" -> list(text)
    )(DeployParameterForm)(DeployParameterForm.unapply)
  )

  def deploy = AuthAction { implicit request =>
    Ok(views.html.deploy.form(request, deployForm))
  }

  def processForm = AuthAction { implicit request =>
    deployForm.bindFromRequest().fold(
      errors => BadRequest(views.html.deploy.form(request, errors)),
      form => {
        log.info(s"Host list: ${form.hosts}")
        val defaultRecipe = LookupSelector().data
          .datum("default-recipe", App(form.project), Stage(form.stage), UnnamedStack)
          .map(data => RecipeName(data.value)).getOrElse(DefaultRecipe())
        val parameters = new DeployParameters(Deployer(request.user.fullName),
          Build(form.project, form.build.toString),
          Stage(form.stage),
          recipe = form.recipe.map(RecipeName).getOrElse(defaultRecipe),
          stacks = form.stacks.map(NamedStack(_)).toSeq,
          hostList = form.hosts)

        form.action match {
          case "preview" =>
            Redirect(routes.DeployController.preview(parameters.build.projectName, parameters.build.id, parameters.stage.name, parameters.recipe.name, parameters.hostList.mkString(","), ""))
          case "deploy" =>
            val uuid = Deployments.deploy(parameters)
            Redirect(routes.DeployController.viewUUID(uuid.toString))
          case _ => throw new RuntimeException("Unknown action")
        }
      }
    )
  }

  def stop(uuid: String) = AuthAction { implicit request =>
    Deployments.stop(UUID.fromString(uuid), request.user.fullName)
    Redirect(routes.DeployController.viewUUID(uuid))
  }

  def viewUUID(uuidString: String, verbose: Boolean) = AuthAction { implicit request =>
    val uuid = UUID.fromString(uuidString)
    val record = Deployments.get(uuid)
    val stopFlag = if (record.isDone) false else Deployments.getStopFlag(uuid).getOrElse(false)
    Ok(views.html.deploy.viewDeploy(request, record, verbose, stopFlag))
  }

  def updatesUUID(uuid: String) = AuthAction { implicit request =>
    val record = Deployments.get(UUID.fromString(uuid))
    Ok(views.html.deploy.logContent(record))
  }

  def preview(projectName: String, buildId: String, stage: String, recipe: String, hosts: String, stacks: String) = AuthAction { implicit request =>
    val hostList = hosts.split(",").toList.filterNot(_.isEmpty)
    val stackList = stacks.split(",").toList.filterNot(_.isEmpty).map(NamedStack(_))
    val parameters = DeployParameters(Deployer(request.user.fullName), Build(projectName, buildId), Stage(stage), RecipeName(recipe), stackList, hostList)
    val previewId = PreviewController.startPreview(parameters)
    Ok(views.html.deploy.preview(request, parameters, previewId.toString))
  }

  def previewContent(previewId: String, projectName: String, buildId: String, stage: String, recipe: String, hosts: String) = AuthAction { implicit request =>
    val previewUUID = UUID.fromString(previewId)
    val hostList = hosts.split(",").toList.filterNot(_.isEmpty)
    val parameters = DeployParameters(
      Deployer(request.user.fullName), Build(projectName, buildId), Stage(stage), RecipeName(recipe), Seq(), hostList
    )
    val result = PreviewController.getPreview(previewUUID, parameters)
    result match {
      case Some(PreviewResult(future, startTime)) =>
        future.value match {
          case Some(Success(preview)) =>
            try {
              Ok(views.html.deploy.previewContent(request, preview))
            } catch {
              case exception: Exception =>
                Ok(views.html.errorContent(exception, "Couldn't resolve preview information."))
            }
          case Some(Failure(exception)) => Ok(views.html.errorContent(exception, "Couldn't retrieve preview information."))
          case None =>
            val duration = new org.joda.time.Duration(startTime, new DateTime())
            Ok(views.html.deploy.previewLoading(request, duration.getStandardSeconds))
        }
      case _ =>
        val exception = new IllegalStateException("Future for preview wasn't found")
        Ok(views.html.errorContent(exception, "Couldn't resolve preview information."))
    }
  }

  def history() = AuthAction { implicit request =>
    Ok(views.html.deploy.history(request))
  }

  def historyContent() = AuthAction { implicit request =>
    val records = try {
      Deployments.getDeploys(deployment.DeployFilter.fromRequest(request), deployment.PaginationView.fromRequest(request), fetchLogs = false).reverse
    } catch {
      case e: Exception =>
        log.error("Exception whilst fetching records", e)
        Nil
    }
    val count = try {
      Some(Deployments.countDeploys(deployment.DeployFilter.fromRequest(request)))
    } catch {
      case e: Exception => None
    }
    Ok(views.html.deploy.historyContent(request, records, deployment.DeployFilterPagination.fromRequest.withItemCount(count)))
  }

  def autoCompleteProject(term: String) = AuthAction {
    val possibleProjects = TeamCityBuilds.jobs.map(_.name).filter(_.toLowerCase.contains(term.toLowerCase)).toList.sorted.take(10)
    Ok(Json.toJson(possibleProjects))
  }

  def autoCompleteBuild(project: String, term: String) = AuthAction {
    val possibleProjects = TeamCityBuilds.successfulBuilds(project).filter(
      p => p.number.contains(term) || p.branchName.contains(term)
    ).map { build =>
      val formatter = DateTimeFormat.forPattern("HH:mm d/M/yy")
      val label = "%s [%s] (%s)" format(build.number, build.branchName, formatter.print(build.startTime))
      Map("label" -> label, "value" -> build.number)
    }
    Ok(Json.toJson(possibleProjects))
  }

  def projectHistory(project: String) = AuthAction {
    if (project.trim.isEmpty) {
      Ok("")
    } else {
      val buildMap = Deployments.getLastCompletedDeploys(project)
      Ok(views.html.deploy.projectHistory(project, buildMap))
    }
  }

  def buildInfo(project: String, build: String) = AuthAction.async {
    log.info(s"Getting build info for $project: $build")
    val buildTagTuple = for {
      b <- Future(TeamCityBuilds.build(project, build).get)
      tagsOption <- TeamCityAPI.tags(b)
      tags <- Future(tagsOption.get)
    } yield (b, tags)

    buildTagTuple map { case (b, tags) =>
      Ok(views.html.deploy.buildInfo(b, tags.map(TagClassification.apply)))
    } recover {
      case e: NoSuchElementException => Ok("")
    }
  }

  def teamcity = AuthAction {
    val header = Seq("Build Type Name", "Build Number", "Build Branch", "Build Type ID", "Build ID")
    val data =
      for (build <- TeamCityBuilds.builds.sortBy(_.jobName))
      yield Seq(build.jobName, build.number, build.branchName, build.jobId, build.id)

    Ok((header :: data.toList).map(_.mkString(",")).mkString("\n")).as("text/csv")
  }

  def deployConfirmation(deployFormJson: String) = AuthAction { implicit request =>
    val parametersJson = Json.parse(deployFormJson)
    Ok(views.html.deploy.deployConfirmation(request, deployForm.bind(parametersJson)))
  }

  def deployConfirmationWithParameters = AuthAction { implicit request =>
    val form = deployForm.bindFromRequest()
    Ok(views.html.deploy.deployConfirmation(request, form))
  }

  def markAsFailed = AuthAction { implicit request =>
    uuidForm.bindFromRequest().fold(
      errors => Redirect(routes.DeployController.history),
      form => {
        form.action match {
          case "markAsFailed" =>
            val record = Deployments.get(UUID.fromString(form.uuid))
            if (record.isStalled)
              Deployments.markAsFailed(record)
            Redirect(routes.DeployController.viewUUID(form.uuid))
        }
      }
    )
  }

  def dashboard(projects: String, search: Boolean) = AuthAction { implicit request =>
    Ok(views.html.deploy.dashboard(request, projects, search))
  }

  def dashboardContent(projects: String, search: Boolean) = AuthAction { implicit request =>
    val projectTerms = projects.split(",").toList.filterNot("" ==)
    val projectNames = if (search) {
      projectTerms.flatMap(term => {
        Deployments.findProjects().filter(_.contains(term))
      })
    } else projectTerms
    val deploys = projectNames.map { project =>
      project -> Deployments.getLastCompletedDeploys(project)
    }.filterNot(_._2.isEmpty)
    Ok(views.html.deploy.dashboardContent(deploys))
  }

}