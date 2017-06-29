package controllers

import java.net.URLEncoder
import java.util.UUID

import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import cats.syntax.either._
import ci.{Builds, S3Tag, TagClassification}
import com.amazonaws.services.s3.model.GetObjectRequest
import conf.Configuration
import controllers.forms.{DeployParameterForm, UuidForm}
import deployment._
import magenta._
import magenta.artifact._
import magenta.deployment_type.DeploymentType
import magenta.input.{All, DeploymentKey, DeploymentKeysSelector}
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import persistence.RestrictionConfigDynamoRepository
import play.api.http.HttpEntity
import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc.{BaseController, ControllerComponents}
import resources.PrismLookup
import restrictions.RestrictionChecker

import scala.util.{Failure, Success}

class DeployController(
  deployments: Deployments, prismLookup: PrismLookup, deploymentTypes: Seq[DeploymentType],
  buildSource: Builds, val controllerComponents: ControllerComponents)
  (implicit val wsClient: WSClient) extends BaseController with Logging with LoginActions with I18nSupport {

  def deploy = AuthAction { implicit request =>
    Ok(views.html.deploy.form(DeployParameterForm.form, prismLookup))
  }

  def processForm = AuthAction { implicit request =>
    DeployParameterForm.form.bindFromRequest().fold(
      errors => {
        logger.info(s"Errors: ${errors.errors}")
        BadRequest(views.html.deploy.form(errors, prismLookup))
      },
      form => {
        log.info(s"Host list: ${form.hosts}")
        val defaultRecipe = prismLookup.data
          .datum("default-recipe", App(form.project), Stage(form.stage), UnnamedStack)
          .map(data => RecipeName(data.value)).getOrElse(DefaultRecipe())
        val parameters = new DeployParameters(Deployer(request.user.fullName),
          Build(form.project, form.build.toString),
          Stage(form.stage),
          recipe = form.recipe.map(RecipeName).getOrElse(defaultRecipe),
          stacks = form.stacks.map(NamedStack(_)),
          hostList = form.hosts,
          selector = form.makeSelector
        )

        form.action match {
          case "preview" =>
            val maybeKeys = parameters.selector match {
              case All => None
              case DeploymentKeysSelector(keys) => Some(DeploymentKey.asString(keys))
            }
            Redirect(routes.PreviewController.preview(
              parameters.build.projectName, parameters.build.id, parameters.stage.name, maybeKeys)
            ).flashing(
              "previewRecipe" -> parameters.recipe.name,
              "previewHosts" -> parameters.hostList.mkString(","),
              "previewStacks" -> parameters.stacks.flatMap(_.nameOption).mkString(",")
            )
          case "deploy" =>
            val uuid = deployments.deploy(parameters, requestSource = UserRequestSource(request.user)).valueOr{ error =>
              throw new IllegalStateException(error.message)
            }
            Redirect(routes.DeployController.viewUUID(uuid.toString))
          case _ => throw new RuntimeException("Unknown action")
        }
      }
    )
  }

  def stop(uuid: String) = AuthAction { implicit request =>
    deployments.stop(UUID.fromString(uuid), request.user.fullName)
    Redirect(routes.DeployController.viewUUID(uuid))
  }

  def viewUUID(uuidString: String, verbose: Boolean) = AuthAction { implicit request =>
    val uuid = UUID.fromString(uuidString)
    val record = deployments.get(uuid)
    val stopFlag = if (record.isDone) false else deployments.getStopFlag(uuid)
    Ok(views.html.deploy.viewDeploy(request, record, verbose, stopFlag))
  }

  def updatesUUID(uuid: String) = AuthAction { implicit request =>
    val record = deployments.get(UUID.fromString(uuid))
    Ok(views.html.deploy.logContent(record))
  }

  def preview(projectName: String, buildId: String, stage: String, recipe: String, hosts: String, stacks: String) = AuthAction { implicit request =>
    val hostList = hosts.split(",").toList.filterNot(_.isEmpty)
    val stackList = stacks.split(",").toList.filterNot(_.isEmpty).map(NamedStack(_))
    val parameters = DeployParameters(Deployer(request.user.fullName), Build(projectName, buildId), Stage(stage), RecipeName(recipe), stackList, hostList)
    val previewId = LegacyPreviewController.startPreview(parameters, prismLookup, deploymentTypes)
    Ok(views.html.preview.json.preview(request, parameters, previewId.toString))
  }

  def previewContent(previewId: String, projectName: String, buildId: String, stage: String, recipe: String, hosts: String) =
    AuthAction { implicit request =>
      val previewUUID = UUID.fromString(previewId)
      val hostList = hosts.split(",").toList.filterNot(_.isEmpty)
      val parameters = DeployParameters(
        Deployer(request.user.fullName), Build(projectName, buildId), Stage(stage), RecipeName(recipe), Seq(), hostList
      )
      val result = LegacyPreviewController.getPreview(previewUUID, parameters)
      result match {
        case Some(LegacyPreviewResult(future, startTime)) =>
          future.value match {
            case Some(Success(preview)) =>
              try {
                Ok(views.html.preview.json.content(request, preview))
              } catch {
                case exception: Exception =>
                  Ok(views.html.errorContent(exception, "Couldn't resolve preview information."))
              }
            case Some(Failure(exception)) => Ok(views.html.errorContent(exception, "Couldn't retrieve preview information."))
            case None =>
              val duration = new org.joda.time.Duration(startTime, new DateTime())
              Ok(views.html.preview.json.loading(request, duration.getStandardSeconds))
          }
        case _ =>
          val exception = new IllegalStateException("Future for preview wasn't found")
          Ok(views.html.errorContent(exception, "Couldn't resolve preview information."))
      }
    }

  def history() = AuthAction { implicit request =>
    Ok(views.html.deploy.history(prismLookup))
  }

  def historyContent() = AuthAction { implicit request =>
    val records = try {
      deployments.getDeploys(deployment.DeployFilter.fromRequest(request), deployment.PaginationView.fromRequest(request), fetchLogs = false).reverse
    } catch {
      case e: Exception =>
        log.error("Exception whilst fetching records", e)
        Nil
    }
    val count = try {
      Some(deployments.countDeploys(deployment.DeployFilter.fromRequest(request)))
    } catch {
      case e: Exception => None
    }
    Ok(views.html.deploy.historyContent(request, records, deployment.DeployFilterPagination.fromRequest.withItemCount(count)))
  }

  def autoCompleteProject(term: String) = AuthAction {
    val possibleProjects = buildSource.jobs.map(_.name).filter(_.toLowerCase.contains(term.toLowerCase)).toList.sorted.take(10)
    Ok(Json.toJson(possibleProjects))
  }

  val shortFormat = DateTimeFormat.forPattern("HH:mm d/M/yy").withZone(DateTimeZone.forID("Europe/London"))

  def autoCompleteBuild(project: String, term: String) = AuthAction {
    val possibleProjects = buildSource.successfulBuilds(project).filter(
      p => p.number.contains(term) || p.branchName.contains(term)
    ).map { build =>
      val label = "%s [%s] (%s)" format(build.number, build.branchName, shortFormat.print(build.startTime))
      Map("label" -> label, "value" -> build.number)
    }
    Ok(Json.toJson(possibleProjects))
  }

  def deployHistory(project: String, maybeStage: Option[String]) = AuthAction { request =>
    if (project.trim.isEmpty) {
      Ok("")
    } else {
      val restrictions = maybeStage.toSeq.flatMap { stage =>
        RestrictionChecker.configsThatPreventDeployment(RestrictionConfigDynamoRepository, project, stage, UserRequestSource(request.user))
      }
      val filter = DeployFilter(projectName = Some(s"^$project$$"), stage = maybeStage)
      val records = deployments.getDeploys(Some(filter), PaginationView(pageSize = Some(5)), fetchLogs = false).reverse
      Ok(views.html.deploy.deployHistory(project, maybeStage, records, restrictions))
    }
  }

  def buildInfo(project: String, build: String) = AuthAction {
    log.info(s"Getting build info for $project: $build")
    val buildTagTuple = for {
      b <- buildSource.build(project, build)
      tags <- S3Tag.of(b)
    } yield (b, tags)

    buildTagTuple map { case (b, tags) =>
      Ok(views.html.deploy.buildInfo(b, tags.map(TagClassification.apply)))
    } getOrElse Ok("")
  }

  def builds = AuthAction {
    val header = Seq("Build Type Name", "Build Number", "Build Branch", "Build Type ID", "Build ID")
    val data =
      for (build <- buildSource.all.sortBy(_.jobName))
      yield Seq(build.jobName, build.number, build.branchName, build.jobId, build.id)

    Ok((header :: data.toList).map(_.mkString(",")).mkString("\n")).as("text/csv")
  }

  def deployConfirmation(deployFormJson: String) = AuthAction { implicit request =>
    val parametersJson = Json.parse(deployFormJson)
    Ok(views.html.deploy.deployConfirmation(DeployParameterForm.form.bind(parametersJson), isExternal = true))
  }

  def deployConfirmationExternal = AuthAction { implicit request =>
    val form = DeployParameterForm.form.bindFromRequest()
    Ok(views.html.deploy.deployConfirmation(form, isExternal = true))
  }

  def deployAgain = AuthAction { implicit request =>
    val form = DeployParameterForm.form.bindFromRequest()
    Ok(views.html.deploy.deployConfirmation(form, isExternal = false))
  }

  def deployAgainUuid(uuidString: String) = AuthAction { implicit request =>
    val uuid = UUID.fromString(uuidString)
    val record = deployments.get(uuid)

    val keys = record.parameters.selector match {
      case DeploymentKeysSelector(keyList) => keyList
      case All => Nil
    }

    val params = DeployParameterForm(record.buildName, record.buildId, record.stage.name,
      Some(record.recipe.name), "deploy", Nil, record.stacks.toList.flatMap(_.nameOption), keys, None)

    val fields = DeployParameterForm.form.fill(params).data

    def queryString = fields.map {
      case (k, v) => k + "=" + URLEncoder.encode(v, "UTF-8")
    }.mkString("&")

    val url = s"${routes.DeployController.deployAgain().url}?$queryString"
    Redirect(url)
  }

  def markAsFailed = AuthAction { implicit request =>
    UuidForm.form.bindFromRequest().fold(
      errors => Redirect(routes.DeployController.history),
      form => {
        form.action match {
          case "markAsFailed" =>
            val record = deployments.get(UUID.fromString(form.uuid))
            if (record.isStalled)
              deployments.markAsFailed(record)
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
        deployments.findProjects().filter(_.contains(term))
      })
    } else projectTerms
    val deploys = projectNames.map { project =>
      project -> deployments.getLastCompletedDeploys(project)
    }.filterNot(_._2.isEmpty)
    Ok(views.html.deploy.dashboardContent(deploys))
  }

  def deployConfig(projectName: String, id: String) = AuthAction { implicit request =>
    def pathAndContent(artifact: S3Artifact): Either[S3Error, (S3Path, String)] = {
      val deployObjectPath = artifact.deployObject
      val deployObjectContent = S3Location.fetchContentAsString(deployObjectPath)(Configuration.artifact.aws.client)
      deployObjectContent.map(deployObjectPath -> _)
    }

    val build = Build(projectName, id)
    val deployObject = pathAndContent(S3YamlArtifact(build, Configuration.artifact.aws.bucketName))
      .orElse(pathAndContent(S3JsonArtifact(build, Configuration.artifact.aws.bucketName)))

    deployObject.map {
      case (path, contents) => Ok(contents).as(path.extension.map {
        case "json" => "application/json"
        case "yaml" => "text/vnd-yaml"
      }.getOrElse("text/plain"))
    }.getOrElse(NotFound(s"Deploy file not found for $projectName $id"))
  }

  def deployFiles(projectName: String, id: String) = AuthAction { implicit request =>
    def pathAndContent(artifact: S3Artifact): Either[S3Error, (S3Artifact, S3Path, String)] = {
      val deployObjectPath = artifact.deployObject
      val deployObjectContent = S3Location.fetchContentAsString(deployObjectPath)(Configuration.artifact.aws.client)
      deployObjectContent.map(content => (artifact, deployObjectPath, content))
    }

    val build = Build(projectName, id)
    val deployObject = pathAndContent(S3YamlArtifact(build, Configuration.artifact.aws.bucketName))
      .orElse(pathAndContent(S3JsonArtifact(build, Configuration.artifact.aws.bucketName)))

    deployObject.map { case (artifact, path, configFile) =>
      val objects = artifact.listAll()(Configuration.artifact.aws.client)
      val relativeObjects = objects.map{ obj => obj.relativeTo(artifact) -> obj}
      Ok(views.html.artifact.listFiles(request, projectName, id, relativeObjects))
    } getOrElse {
      NotFound("Project not found")
    }
  }

  def getArtifactFile(key: String) = AuthAction { implicit request =>
    val s3doc = Configuration.artifact.aws.client.getObject(new GetObjectRequest(Configuration.artifact.aws.bucketName, key))
    val stream = s3doc.getObjectContent
    val source: Source[ByteString, _] = StreamConverters.fromInputStream(() => stream)
    Ok.sendEntity(HttpEntity.Streamed(source, None, Some(""))).as(s3doc.getObjectMetadata.getContentType)
  }

}