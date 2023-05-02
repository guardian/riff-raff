package magenta.deployment_type

import magenta.KeyRing
import magenta.tasks.UpdateFastlyPackage
import software.amazon.awssdk.services.s3.S3Client

object FastlyCompute extends DeploymentType {
  val name = "fastly-compute"

  val documentation: String =
    """
      |Deploy a [Compute@Edge](https://www.fastly.com/products/edge-compute) package via the Fastly API.
    """.stripMargin

  val deploy: Action = Action(
    "deploy",
    """
      |Undertakes the following using the Fastly API:
      |
      | - Clone the currently active version
      | - Uploads the package
      |
      |Note that `your-service` must match the deployment resource name
      | ```
      |  your-service:
      |   type: fastly-compute-edge
      |```
    """.stripMargin
  ) { (pkg, resources, target) =>
    implicit val keyRing: KeyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient: S3Client = resources.artifactClient
    resources.reporter.verbose(s"Keyring is $keyRing")
    List(UpdateFastlyPackage(pkg.s3Package)(keyRing, artifactClient))
  }

  def defaultActions: List[Action] = List(deploy)
}
