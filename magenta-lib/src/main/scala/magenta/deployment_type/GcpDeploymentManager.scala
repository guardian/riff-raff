package magenta.deployment_type

import cats.instances.list._
import cats.instances.either._
import cats.syntax.either._
import cats.syntax.traverse._
import magenta.artifact.{EmptyS3Location, S3Location, S3Path, UnknownS3Error}
import magenta.input.RiffRaffYamlReader
import magenta.tasks.gcp.DeploymentManagerTasks
import magenta.tasks.gcp.Gcp.DeploymentManagerApi.DeploymentBundle
import magenta.{DeployReporter, KeyRing}
import software.amazon.awssdk.services.s3.S3Client

import scala.concurrent.duration._

object GcpDeploymentManager extends DeploymentType {
  val GCP_PROJECT_NAME_PRISM_KEY: String = "gcp:project-name"

  override def name: String = "gcp-deployment-manager"

  override def documentation: String =
    """
      |
      |""".stripMargin

  val maxWaitParam: Param[Int] = Param[Int](
    name = "maxWait",
    documentation = """
      |Number of seconds to wait for the deployment operations to complete
      |""".stripMargin
  ).default(1800) // half an hour

  val deploymentNameParam: Param[String] = Param(
    name = "deploymentName",
    documentation =
      """
        |The name of the deployment that this config should be applied to
        |""".stripMargin
  )

  val configPathParam: Param[String] = Param[String](
    name = "configPath",
    documentation =
      """
        |The path of the config in the package
        |""".stripMargin
  )

  val configPathByStageParam: Param[Map[String, String]] = Param[Map[String, String]](
    name = "configPathByStage",
    documentation =
      """
        |A dict of stages to paths of config files in the package. When the current stage is found in here the config file
        |will be used from this dict. If it's not found here then it will fall back to `configPath` if it exists.
        |```
        |configPathByStage:
        |  PROD: config-file-prod.yaml
        |  CODE: config-file-code.yaml
        |```
        |""".stripMargin
  )

  val updateAction: Action = Action(
    name = "update",
    documentation =
      """
        |Update the deployment
        |""".stripMargin
  )
  { (pkg, resources, target) =>
    {
      implicit val keyRing: KeyRing = resources.assembleKeyring(target, pkg)
      val reporter: DeployReporter = resources.reporter

      val projectName = resources.lookup.data.datum(GCP_PROJECT_NAME_PRISM_KEY, pkg.app, target.parameters.stage, target.stack).getOrElse{
        reporter.fail(s"Couldn't lookup a valid gcp:project-name from ${resources.lookup.name} for ${pkg.app}, ${target.parameters.stage}, ${target.stack}")
      }
      implicit val artifactClient: S3Client = resources.artifactClient

      val maxWaitDuration = maxWaitParam(pkg, target, reporter).seconds
      val deploymentName = deploymentNameParam(pkg, target, reporter)

      val configPathMap = (configPathByStageParam.get(pkg), configPathParam.get(pkg)) match {
        case (maybeMap, Some(default)) => maybeMap.getOrElse(Map.empty).withDefaultValue(default)
        case (maybeMap, None) => maybeMap.getOrElse(Map.empty)
      }

      val configPath = configPathMap.getOrElse(
        target.parameters.stage.name,
        reporter.fail(s"No config path supplied for stage ${target.parameters.stage.name} in configPathByStage or configStage")
      )

      val maybeBundle = createDeploymentBundle(pkg.s3Package, configPath, reporter)
      val bundle = maybeBundle.fold(
        error => reporter.fail(s"Failed to create deployment manager bundle: ${error.error}", error.maybeThrowable),
        identity
      )
      List(
        DeploymentManagerTasks.updateTask(projectName.value, deploymentName, bundle, maxWaitDuration)
      )
    }
  }

  override def defaultActions: List[Action] = List(updateAction)

  sealed trait BundleError {
    val error: String
    val maybeThrowable: Option[Throwable] = None
  }
  case class ConfigMissingError(path: S3Location) extends BundleError {
    val error = s"Couldn't find deployment manager config at s3://${path.bucket}/${path.key}"
  }
  case class UnknownBundleError(message: String, t: Throwable) extends BundleError {
    val error = s"Unknown error: $message"
    override val maybeThrowable: Option[Throwable] = Some(t)
  }

  def createDeploymentBundle(packageRoot: S3Path, configFile: String, reporter: DeployReporter)(implicit artifactClient: S3Client): Either[BundleError, DeploymentBundle] = {
    val configFileLocation = S3Path(packageRoot, configFile)
    for {
      yaml <- getFileContent(configFileLocation)
      json <- Either.catchNonFatal(RiffRaffYamlReader.yamlToJson(yaml)).leftMap(t => UnknownBundleError("Couldn't parse YAML config file", t))
      dependencies = (json \ "dependencies").asOpt[List[String]].getOrElse(Nil)
      dependenciesMap <- dependencies.traverse { dependency =>
        val s3Dep = S3Path(packageRoot, dependency)
        getFileContent(s3Dep).map (dependency ->)
      }
    } yield DeploymentBundle(configFile, yaml, dependenciesMap.toMap)
  }

  def getFileContent(file: S3Location)(implicit artifactClient: S3Client): Either[BundleError, String] = {
    file.fetchContentAsString().leftMap {
      case EmptyS3Location(location) => ConfigMissingError(location)
      case UnknownS3Error(t) => UnknownBundleError(s"Deployment file fetch failure from s3://${file.bucket}/${file.key}", t)
    }
  }
}
