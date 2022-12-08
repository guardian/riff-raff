package magenta.deployment_type

import magenta.artifact.S3Path
import magenta.tasks.{SSM, UpdateS3LambdaLayer}
import magenta.{DeployReporter, DeployTarget, DeploymentPackage, DeploymentResources, KeyRing, Region, tasks}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.ssm.SsmClient

object LambdaLayer extends LambdaLayer

trait LambdaLayer extends DeploymentType with BucketParameters {
  val name = "aws-lambda-layer"
  val documentation =
    """
      |Provides deploy actions to upload and update Lambda layers.
      |
      """.stripMargin

  val layerNameParam = Param[String]("layerName",
    """The layer name to update with the code from fileNameParam.
      |""".stripMargin,
    optional = false
  )

  val fileNameParam = Param[String](
    "fileName",
    "The name of the archive of the function",
    deprecatedDefault = true
  )
    .defaultFromContext((pkg, _) => Right(s"${pkg.name}.zip"))

  val prefixStackToKeyParam = Param[Boolean](
    "prefixStackToKey",
    documentation = "Whether to prefix `package` to the S3 location"
  ).default(true)

  def withSsm[T](
                  keyRing: KeyRing,
                  region: Region,
                  resources: DeploymentResources
                ): (SsmClient => T) => T = SSM.withSsmClient[T](keyRing, region, resources)

  def makeS3Key(
                 target: DeployTarget,
                 pkg: DeploymentPackage,
                 fileName: String,
                 reporter: DeployReporter
               ): String = {
    val prefixStack = prefixStackToKeyParam(pkg, target, reporter)
    val prefix = if (prefixStack) List(target.stack.name) else Nil
    (prefix ::: List(target.parameters.stage.name, pkg.app.name, fileName))
      .mkString("/")
  }

  def layerToProcess(
                       pkg: DeploymentPackage,
                       target: DeployTarget,
                       reporter: DeployReporter
                     ): UpdateLambdaLayer = {
    val bucket = getTargetBucketFromConfig(pkg, target, reporter)
    val stage = target.parameters.stage.name

    layerNameParam.get(pkg) match {
      case Some(name) => UpdateLambdaLayer(
        layer = LambdaLayerName(s"$name$stage"),
        fileName = fileNameParam(pkg, target, reporter),
        region = target.region,
        s3Bucket = bucket
      )
      case None => reporter.fail(s"Lambda layer name not defined")
    }
  }

  val uploadLayer = Action(
    "uploadLayer",
    """
      |Uploads the lambda layer to S3.
    """.stripMargin
  ) { (pkg, resources, target) =>
    implicit val keyRing: KeyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient: S3Client = resources.artifactClient

    val layer = layerToProcess(
      pkg = pkg,
      target = target,
      reporter = resources.reporter
    )

    val s3Bucket = tasks.S3.getBucketName(
      bucket = layer.s3Bucket,
      withSsmClient = withSsm(keyRing, target.region, resources),
      reporter = resources.reporter
    )

    val s3Key = makeS3Key(target, pkg, layer.fileName, resources.reporter)
    val uploadTask = tasks.S3Upload(
      region = layer.region,
      bucket = s3Bucket,
      paths = Seq(S3Path(pkg.s3Package, layer.fileName) -> s3Key)
    )

    List(uploadTask)
  }

  val updateLayer = Action(
    "updateLayer",
    """
      |Updates the lambda layer to use new code using the PublishLayerVersion API.
    """.stripMargin
  ) { (pkg, resources, target) =>
    implicit val keyRing: KeyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient: S3Client = resources.artifactClient

    val updateLayer = layerToProcess(
      pkg = pkg,
      target = target,
      reporter = resources.reporter
    )

    val s3Bucket = tasks.S3.getBucketName(
      bucket = updateLayer.s3Bucket,
      withSsmClient = withSsm(keyRing, target.region, resources),
      reporter = resources.reporter
    )

    val s3Key = makeS3Key(
      target = target,
      pkg = pkg,
      fileName = updateLayer.fileName,
      reporter = resources.reporter
    )

    val updateTask = UpdateS3LambdaLayer(
      updateLayer.layer,
      s3Bucket,
      s3Key,
      updateLayer.region
    )

    List(updateTask)
  }

  def defaultActions = List(uploadLayer, updateLayer)
}

case class LambdaLayerName(name: String)
case class UpdateLambdaLayer(
  layer: LambdaLayerName,
  fileName: String,
  region: Region,
  s3Bucket: tasks.S3.Bucket
)