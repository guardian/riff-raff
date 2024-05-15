package controllers

import java.net.{URLDecoder, URLEncoder}
import java.util.UUID
import org.apache.pekko.stream.scaladsl.{Source, StreamConverters}
import org.apache.pekko.util.ByteString
import cats.syntax.either._
import ci.{Builds, S3Tag, TagClassification}
import com.gu.googleauth.AuthAction
import conf.Config
import controllers.forms.{DeployParameterForm, UuidForm}
import deployment._
import magenta._
import magenta.artifact._
import magenta.deployment_type.DeploymentType
import magenta.input.{All, DeploymentKey, DeploymentKeysSelector}
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import persistence.RestrictionConfigDynamoRepository
import play.api.http.HttpEntity
import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc.{AnyContent, BaseController, ControllerComponents}
import resources.PrismLookup
import restrictions.RestrictionChecker
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import utils.{ChangeFreeze, LogAndSquashBehaviour, VCSInfo}
import magenta.input.RiffRaffYamlReader

class DeployController(
    config: Config,
    menu: Menu,
    deployments: Deployments,
    prismLookup: PrismLookup,
    deploymentTypes: Seq[DeploymentType],
    changeFreeze: ChangeFreeze,
    buildSource: Builds,
    s3Tag: S3Tag,
    AuthAction: AuthAction[AnyContent],
    restrictionConfigDynamoRepository: RestrictionConfigDynamoRepository,
    val controllerComponents: ControllerComponents
)(implicit val wsClient: WSClient)
    extends BaseController
    with Logging
    with I18nSupport
    with LogAndSquashBehaviour {

  def deploy = AuthAction { implicit request =>
    Ok(
      views.html.deploy
        .form(config, menu)(changeFreeze)(DeployParameterForm.form, prismLookup)
    )
  }

  def processForm = AuthAction { implicit request =>
    DeployParameterForm.form
      .bindFromRequest()
      .fold(
        errors => {
          logger.info(s"Errors: ${errors.errors}")
          BadRequest(
            views.html.deploy
              .form(config, menu)(changeFreeze)(errors, prismLookup)
          )
        },
        form => {
          val parameters = DeployParameters(
            Deployer(request.user.fullName),
            Build(form.project, form.build.toString),
            Stage(form.stage),
            selector = form.makeSelector,
            updateStrategy = form.updateStrategy
          )

          form.action match {
            case "preview" =>
              val maybeKeys = parameters.selector match {
                case All => None
                case DeploymentKeysSelector(keys) =>
                  Some(DeploymentKey.asString(keys))
              }
              Redirect(
                routes.PreviewController.preview(
                  parameters.build.projectName,
                  parameters.build.id,
                  parameters.stage.name,
                  maybeKeys,
                  parameters.updateStrategy
                )
              )
            case "deploy" =>
              val uuid = deployments
                .deploy(
                  parameters,
                  requestSource = UserRequestSource(request.user)
                )
                .valueOr { error =>
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

  def viewUUID(uuidString: String, verbose: Boolean) = AuthAction {
    implicit request =>
      val uuid = UUID.fromString(uuidString)
      val record = deployments.get(uuid)
      val stopFlag = if (record.isDone) false else deployments.getStopFlag(uuid)
      Ok(
        views.html.deploy
          .viewDeploy(config, menu)(request, record, verbose, stopFlag)
      )
  }

  def updatesUUID(uuid: String) = AuthAction { implicit request =>
    val record = deployments.get(UUID.fromString(uuid))
    Ok(views.html.deploy.logContent(record))
  }

  def history() = AuthAction { implicit request =>
    Ok(views.html.deploy.history(config, menu)(prismLookup))
  }

  def historyContent() = AuthAction { implicit request =>
    val records =
      try {
        deployments
          .getDeploys(
            deployment.DeployFilter.fromRequest(request),
            deployment.PaginationView.fromRequest(request),
            fetchLogs = false
          )
          .logAndSquashException(Nil)
          .reverse
      } catch {
        case e: Exception =>
          log.error("Exception whilst fetching records", e)
          Nil
      }
    val count =
      try {
        Some(
          deployments.countDeploys(deployment.DeployFilter.fromRequest(request))
        )
      } catch {
        case e: Exception => None
      }
    Ok(
      views.html.deploy.historyContent(
        request,
        records,
        deployment.DeployFilterPagination.fromRequest.withItemCount(count)
      )
    )
  }

  def autoCompleteProject(term: String) = AuthAction {
    val possibleProjects = buildSource.jobs
      .map(_.name)
      .filter(_.toLowerCase.contains(term.toLowerCase))
      .toList
      .sorted
      .take(10)
    Ok(Json.toJson(possibleProjects))
  }

  val shortFormat = DateTimeFormat
    .forPattern("HH:mm d/M/yy")
    .withZone(DateTimeZone.forID("Europe/London"))

  def autoCompleteBuild(project: String, term: String) = AuthAction {
    val possibleProjects = buildSource
      .successfulBuilds(project)
      .filter(p => p.number.contains(term) || p.branchName.contains(term))
      .map { build =>
        val label =
          "%s [%s] (%s) (%s)" format (build.number, build.branchName, shortFormat
            .print(build.startTime), VCSInfo
            .normalise(build.vcsURL)
            .getOrElse(build.vcsURL))
        Map("label" -> label, "value" -> build.number)
      }
    Ok(Json.toJson(possibleProjects))
  }

  def allowedStages(project: String, id: String) = AuthAction { request =>
    val allStages = prismLookup.stages
    def asJsonResponse(stages: Seq[String]) =
      Ok(Json.toJson(stages).toString()).as("application/json")

    def defaultStage(stages: Seq[String]): Option[String] = {
      val sorted = stages.sorted
      val code = sorted.find(stage => stage.contains("CODE"))
      val nonProd = sorted.find(stage => !stage.contains("PROD"))
      code.orElse(nonProd).orElse(stages.headOption)
    }

    def asHtmlResponse(stages: Seq[String], selected: Option[String]) = {
      val stageOptions = stages.map(stage => {
        val isSelected = if (selected.contains(stage)) "selected" else ""
        s"<option ${isSelected} value=\"${stage}\">${stage}</option>"
      })

      val options =
        s"""<option value="" class="blank">--- Choose a stage ---</option>${stageOptions.mkString}""".stripMargin;

      Ok(options)
    }

    if (project.trim.isEmpty || id.trim.isEmpty) {
      asHtmlResponse(allStages, None)
    } else {
      val build = Build(project, id)
      val artifact = S3YamlArtifact(build, config.artifact.aws.bucketName)
      val deployObjectPath = artifact.deployObject
      val deployObjectContent = S3Location.fetchContentAsString(
        deployObjectPath
      )(config.artifact.aws.client)

      val allowedStages = for {
        content <- deployObjectContent.map(Some.apply).getOrElse(None)
        config <- RiffRaffYamlReader.fromString(content).toOption
        allowedStages <- config.allowedStages
      } yield allowedStages

      val stages = allowedStages.getOrElse(allStages)
      asHtmlResponse(stages, defaultStage(stages))
    }
  }

  def deployHistory(
      project: String,
      maybeStage: Option[String],
      isExactMatchProjectName: Option[Boolean]
  ) = AuthAction { request =>
    if (project.trim.isEmpty) {
      Ok("")
    } else {
      val restrictions = maybeStage.toSeq.flatMap { stage =>
        RestrictionChecker.configsThatPreventDeployment(
          restrictionConfigDynamoRepository,
          project,
          stage,
          UserRequestSource(request.user)
        )
      }
      val filter = DeployFilter(
        projectName = Some(project),
        stage = maybeStage,
        isExactMatchProjectName = isExactMatchProjectName
      )
      val records = deployments
        .getDeploys(
          Some(filter),
          PaginationView(pageSize = Some(5)),
          fetchLogs = false
        )
        .logAndSquashException(Nil)
        .reverse
      Ok(
        views.html.deploy
          .deployHistory(project, maybeStage, records, restrictions)
      )
    }
  }

  def buildInfo(project: String, build: String) = AuthAction {
    log.info(s"Getting build info for $project: $build")
    val buildTagTuple = for {
      b <- buildSource.build(project, build)
      tags <- s3Tag.of(b)
    } yield (b, tags)

    buildTagTuple map { case (b, tags) =>
      Ok(views.html.deploy.buildInfo(b, tags.map(TagClassification.apply)))
    } getOrElse Ok("")
  }

  def builds = AuthAction {
    val header = Seq(
      "Build Type Name",
      "Build Number",
      "Build Branch",
      "Build Type ID",
      "Build ID"
    )
    val data =
      for (build <- buildSource.all.sortBy(_.jobName))
        yield Seq(
          build.jobName,
          build.number,
          build.branchName,
          build.jobId,
          build.id
        )

    Ok((header :: data.toList).map(_.mkString(",")).mkString("\n"))
      .as("text/csv")
  }

  def deployConfirmation(deployFormJson: String) = AuthAction {
    implicit request =>
      val parametersJson = Json.parse(deployFormJson)
      Ok(
        views.html.deploy.deployConfirmation(config, menu)(
          DeployParameterForm.form.bind(parametersJson),
          isExternal = true
        )
      )
  }

  def deployConfirmationExternal = AuthAction { implicit request =>
    val form = DeployParameterForm.form.bindFromRequest()
    Ok(
      views.html.deploy
        .deployConfirmation(config, menu)(form, isExternal = true)
    )
  }

  def deployAgain = AuthAction { implicit request =>
    val form = DeployParameterForm.form.bindFromRequest()
    Ok(
      views.html.deploy
        .deployConfirmation(config, menu)(form, isExternal = false)
    )
  }

  def deployAgainUuid(uuidString: String) = AuthAction { implicit request =>
    val uuid = UUID.fromString(uuidString)
    val record = deployments.get(uuid)

    val keys = record.parameters.selector match {
      case DeploymentKeysSelector(keyList) => keyList
      case All                             => Nil
    }

    val params = DeployParameterForm(
      record.buildName,
      record.buildId,
      record.stage.name,
      "deploy",
      keys,
      None,
      record.parameters.updateStrategy
    )

    val fields = DeployParameterForm.form.fill(params).data

    def queryString = fields
      .map { case (k, v) =>
        k + "=" + URLEncoder.encode(v, "UTF-8")
      }
      .mkString("&")

    val url = s"${routes.DeployController.deployAgain.url}?$queryString"
    Redirect(url)
  }

  def markAsFailed = AuthAction { implicit request =>
    UuidForm.form
      .bindFromRequest()
      .fold(
        _ => Redirect(routes.DeployController.history()),
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

  def dashboard(projects: String, search: Boolean) = AuthAction {
    implicit request =>
      Ok(views.html.deploy.dashboard(config, menu)(request, projects, search))
  }

  def dashboardContent(projects: String, search: Boolean) = AuthAction {
    implicit request =>
      def go(terms: List[String]): Either[Throwable, List[String]] =
        terms match {
          case Nil => Right(Nil)
          case term :: terms =>
            for {
              psTerm <- deployments.findProjects.map(_.filter(_.contains(term)))
              psTerms <- go(terms)
            } yield psTerm ++ psTerms
        }

      val projectTerms = projects.split(",").toList.filterNot("" ==)
      val projectNames = if (search) go(projectTerms) else Right(projectTerms)
      val deploys = projectNames.map(_.map { project =>
        project -> deployments.getLastCompletedDeploys(project)
      }.filterNot(_._2.isEmpty))
      deploys.fold(
        (t: Throwable) =>
          InternalServerError(
            views.html.errorContent(t, "Could not fetch deploys")
          ),
        (ds: List[(String, Map[String, deployment.Record])]) =>
          Ok(views.html.deploy.dashboardContent(config)(ds))
      )
  }

  def deployConfig(projectName: String, id: String) = AuthAction {
    implicit request =>
      def pathAndContent(
          artifact: S3Artifact
      ): Either[S3Error, (S3Path, String)] = {
        val deployObjectPath = artifact.deployObject
        val deployObjectContent = S3Location.fetchContentAsString(
          deployObjectPath
        )(config.artifact.aws.client)
        deployObjectContent.map(deployObjectPath -> _)
      }

      val build = Build(projectName, id)
      val deployObject =
        pathAndContent(S3YamlArtifact(build, config.artifact.aws.bucketName))

      deployObject
        .map { case (path, contents) =>
          Ok(contents).as(
            path.extension
              .map {
                case "json" => "application/json"
                case "yaml" => "text/vnd-yaml"
              }
              .getOrElse("text/plain")
          )
        }
        .getOrElse(NotFound(s"Deploy file not found for $projectName $id"))
  }

  def deployFiles(projectName: String, id: String) = AuthAction {
    implicit request =>
      def pathAndContent(
          artifact: S3Artifact
      ): Either[S3Error, (S3Artifact, S3Path, String)] = {
        val deployObjectPath = artifact.deployObject
        val deployObjectContent = S3Location.fetchContentAsString(
          deployObjectPath
        )(config.artifact.aws.client)
        deployObjectContent.map(content =>
          (artifact, deployObjectPath, content)
        )
      }

      val build = Build(projectName, id)
      val deployObject =
        pathAndContent(S3YamlArtifact(build, config.artifact.aws.bucketName))

      deployObject.map { case (artifact, path, configFile) =>
        val objects = artifact.listAll()(config.artifact.aws.client)
        val relativeObjects = objects.map { obj =>
          obj.relativeTo(artifact) -> obj
        }
        Ok(
          views.html.artifact
            .listFiles(config, menu)(request, projectName, id, relativeObjects)
        )
      } getOrElse {
        NotFound("Project not found")
      }
  }

  def getArtifactFile(key: String) = AuthAction { implicit request =>
    val getObjectRequest = GetObjectRequest
      .builder()
      .bucket(config.artifact.aws.bucketName)
      .key(URLDecoder.decode(key, "utf-8"))
      .build()

    val stream = config.artifact.aws.client.getObject(getObjectRequest)
    val source: Source[ByteString, _] =
      StreamConverters.fromInputStream(() => stream)
    Ok.sendEntity(HttpEntity.Streamed(source, None, Some("")))
      .as(stream.response.contentType)
  }

}
