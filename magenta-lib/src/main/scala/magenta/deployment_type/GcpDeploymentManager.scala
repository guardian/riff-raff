package magenta.deployment_type

import cats.instances.list._
import cats.instances.either._
import cats.syntax.either._
import cats.syntax.traverse._
import magenta.artifact.{EmptyS3Location, S3Location, S3Path, UnknownS3Error}
import magenta.input.RiffRaffYamlReader
import magenta.tasks.gcp.DeploymentManagerTasks
import magenta.tasks.gcp.GCP.DeploymentManagerApi.DeploymentBundle
import magenta.{DeployReporter, KeyRing}
import software.amazon.awssdk.services.s3.S3Client

import java.time.Duration
import java.time.Duration.ofMinutes

object GcpDeploymentManager extends DeploymentType {
  val GCP_PROJECT_NAME_PRISM_KEY: String = "gcp:project-name"

  override def name: String = "gcp-deployment-manager"

  override def documentation: String =
    """
      |
      |""".stripMargin

  val maxWaitParam: Param[Duration] =
    Param
      .waitingSecondsFor("maxWait", "the deployment operations to complete")
      .default(ofMinutes(30))

  val deploymentNameParam: Param[String] = Param(
    name = "deploymentName",
    documentation = """
        |The name of the deployment that this config should be applied to
        |""".stripMargin
  )

  val configPathParam: Param[String] = Param[String](
    name = "configPath",
    documentation = """
        |The path of the config in the package
        |""".stripMargin,
    optional = true // one of this or configPathByStage
  )

  val configPathByStageParam: Param[Map[String, String]] =
    Param[Map[String, String]](
      name = "configPathByStage",
      documentation = """
        |A dict of stages to paths of config files in the package. When the current stage is found in here the config file
        |will be used from this dict. If it's not found here then it will fall back to `configPath` if it exists.
        |```
        |configPathByStage:
        |  PROD: config-file-prod.yaml
        |  CODE: config-file-code.yaml
        |```
        |""".stripMargin,
      optional = true // one of this or configPath
    )

  val upsertParam: Param[Boolean] = Param[Boolean](
    name = "upsert",
    documentation = """
        |When true a deployment should be created if it doesn't already exist. When false a deployment will fail
        |if it doesn't exist but still updated if it does exist.
        |""".stripMargin
  ).default(false)

  val previewParam: Param[Boolean] = Param[Boolean](
    name = "preview",
    documentation = """
        |Pass through preview to any update or insert operation.
        |""".stripMargin
  ).default(false)

  val updateAction: Action = Action(
    name = "update",
    documentation = """
        |Update the deployment
        |""".stripMargin
  ) { (pkg, resources, target) =>
    {
      implicit val keyRing: KeyRing = resources.assembleKeyring(target, pkg)
      val reporter: DeployReporter = resources.reporter

      val projectName: String =
        keyRing.apiCredentials.get("gcp").map(_.id).getOrElse {
          reporter.fail(
            s"No GCP key on keyring so unable to find project name. Ensure there is a credentials:gcp entry in ${resources.lookup.name} for ${pkg.app}, ${target.parameters.stage}, ${target.stack}"
          )
        }
      implicit val artifactClient: S3Client = resources.artifactClient

      val maxWaitDuration = maxWaitParam(pkg, target, reporter)
      val deploymentName = deploymentNameParam(pkg, target, reporter)
      val upsert = upsertParam(pkg, target, reporter)
      val preview = previewParam(pkg, target, reporter)

      val configPath: String =
        (configPathByStageParam.get(pkg), configPathParam.get(pkg)) match {
          case (Some(map), None) =>
            map.getOrElse(
              target.parameters.stage.name,
              reporter.fail(
                s"No config path supplied for stage ${target.parameters.stage.name} in configPathByStage"
              )
            )
          case (None, Some(path)) => path
          case (None, None) | (Some(_), Some(_)) =>
            reporter.fail(
              s"One and only one of configPathByStage or configPath must be provided"
            )
        }

      val maybeBundle =
        createDeploymentBundle(pkg.s3Package, configPath, reporter)
      val bundle = maybeBundle.fold(
        error =>
          reporter.fail(
            s"Failed to create deployment manager bundle: ${error.error}",
            error.maybeThrowable
          ),
        identity
      )
      List(
        DeploymentManagerTasks.updateTask(
          projectName,
          deploymentName,
          bundle,
          maxWaitDuration,
          upsert,
          preview
        )
      )
    }
  }

  override def defaultActions: List[Action] = List(updateAction)

  sealed trait BundleError {
    val error: String
    val maybeThrowable: Option[Throwable] = None
  }
  case class ConfigMissingError(path: S3Location) extends BundleError {
    val error =
      s"Couldn't find deployment manager config at s3://${path.bucket}/${path.key}"
  }
  case class UnknownBundleError(message: String, t: Throwable)
      extends BundleError {
    val error = s"Unknown error: $message"
    override val maybeThrowable: Option[Throwable] = Some(t)
  }

  def createDeploymentBundle(
      packageRoot: S3Path,
      configFile: String,
      reporter: DeployReporter
  )(implicit
      artifactClient: S3Client
  ): Either[BundleError, DeploymentBundle] = {
    val configFileLocation = S3Path(packageRoot, configFile)
    for {
      yaml <- getFileContent(configFileLocation)
      json <- RiffRaffYamlReader
        .yamlToJson(yaml)
        .toEither
        .leftMap(t => UnknownBundleError("Couldn't parse YAML config file", t))
      dependencies = (json \ "imports" \\ "path").toList
        .flatMap(_.asOpt[String])
      dependenciesMap <- dependencies.traverse { dependency =>
        val s3Dep = S3Path(packageRoot, dependency)
        getFileContent(s3Dep).map(dependency ->)
      }
    } yield DeploymentBundle(configFile, yaml, dependenciesMap.toMap)
  }

  def getFileContent(
      file: S3Location
  )(implicit artifactClient: S3Client): Either[BundleError, String] = {
    file.fetchContentAsString().leftMap {
      case EmptyS3Location(location) => ConfigMissingError(location)
      case UnknownS3Error(t) =>
        UnknownBundleError(
          s"Deployment file fetch failure from s3://${file.bucket}/${file.key}",
          t
        )
    }
  }
}
