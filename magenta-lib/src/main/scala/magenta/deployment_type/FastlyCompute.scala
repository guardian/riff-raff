package magenta.deployment_type

import magenta.{DeploymentResources, KeyRing, Region}
import magenta.deployment_type.AutoScaling.{
  prefixApp,
  prefixPackage,
  prefixStack,
  prefixStage
}
import magenta.tasks.{S3Upload, SSM, UpdateFastlyPackage}
import magenta.tasks.{S3 => S3Tasks}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.ssm.SsmClient

object FastlyCompute extends DeploymentType with BucketParameters {
  val name = "fastly-compute"

  val documentation: String =
    """
      |Deploy a [Compute@Edge](https://www.fastly.com/products/edge-compute) package via the Fastly API.
      |
      |Example:
      |
      |```yaml
      |your-service:
      |  type: fastly-compute
      |```
      |Note that `your-service` must match the App name in Deployment Resources under `credentials:fastly`.
      |Instructions on how to add a new Deployment Resource can be found [here](https://github.com/guardian/deploy-tools-platform/blob/main/cloudformation/riffraff/riff-raff-roles.md#updating-riffraff-configuration)
    """.stripMargin

  // TODO this is copied from `Lambda.scala` and could be DRYed out
  def withSsm[T](
      keyRing: KeyRing,
      region: Region,
      resources: DeploymentResources
  ): (SsmClient => T) => T = SSM.withSsmClient[T](keyRing, region, resources)

  val uploadArtifacts: Action = Action(
    "uploadArtifacts",
    """
      |Uploads the Compute@Edge package in the deployment's directory to the specified bucket.
    """.stripMargin
  ) { (pkg, resources, target) =>
    implicit val keyRing: KeyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient: S3Client = resources.artifactClient
    val reporter = resources.reporter

    val maybePackageOrAppName: Option[String] = (
      prefixPackage(pkg, target, reporter),
      prefixApp(pkg, target, reporter)
    ) match {
      case (_, true)      => Some(pkg.app.name)
      case (true, false)  => Some(pkg.name)
      case (false, false) => None
    }

    val prefix = S3Upload.prefixGenerator(
      stack =
        if (prefixStack(pkg, target, reporter)) Some(target.stack) else None,
      stage =
        if (prefixStage(pkg, target, reporter)) Some(target.parameters.stage)
        else None,
      packageOrAppName = maybePackageOrAppName
    )

    val bucket = getTargetBucketFromConfig(pkg, target, reporter)

    val s3Bucket = S3Tasks.getBucketName(
      bucket,
      withSsm(keyRing, target.region, resources),
      resources.reporter
    )

    List(
      S3Upload(
        target.region,
        s3Bucket,
        Seq(pkg.s3Package -> prefix)
      )
    )
  }

  val deploy: Action = Action(
    "deploy",
    """
      |Undertakes the following using the Fastly API:
      |
      | - Clones the currently active version
      | - Uploads the Compute@Edge package containing the WebAssembly binary and the manifest
      | - Activates the new version
    """.stripMargin
  ) { (pkg, resources, target) =>
    implicit val keyRing: KeyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient: S3Client = resources.artifactClient
    implicit val deployParameters = target.parameters
    resources.reporter.verbose(s"Keyring is $keyRing")
    List(
      UpdateFastlyPackage(pkg.s3Package)(
        keyRing,
        artifactClient,
        deployParameters
      )
    )
  }

  def defaultActions: List[Action] = List(uploadArtifacts, deploy)
}
