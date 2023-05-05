package magenta.deployment_type

import magenta.{DeployParameters, KeyRing}
import magenta.tasks.UpdateFastlyConfigUtils
import software.amazon.awssdk.services.s3.S3Client

object Fastly extends DeploymentType {
  val name = "fastly"
  val documentation: String =
    """
      |Deploy a new set of VCL configuration files to the [fastly](https://www.fastly.com/) CDN via the fastly API.
    """.stripMargin

  val deploy: Action = Action(
    "deploy",
    """
      |Undertakes the following using the fastly API:
      |
      | - Clone the currently active version
      | - Deletes all of the existing files from the new clone
      | - Uploads files from this deployment
      | - Waits for it to compile
      | - Validates the config
      | - Activates the config
      |
      | This will set `main.vcl` as the main entrypoint (without checking that this exists) so make sure to structure
      | your VCL files to facilitate this.
    """.stripMargin
  ) { (pkg, resources, target) =>
    implicit val keyRing: KeyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient: S3Client = resources.artifactClient
    implicit val deployParameters: DeployParameters = target.parameters
    resources.reporter.verbose(s"Keyring is $keyRing")
    List(
      UpdateFastlyConfigUtils(pkg.s3Package)(
        keyRing,
        artifactClient,
        deployParameters
      )
    )
  }

  def defaultActions: List[Action] = List(deploy)
}
