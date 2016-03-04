package magenta.deployment_type

import java.io.File

import magenta.tasks.{ChangeSwitch, S3Upload}

object SelfDeploy extends DeploymentType with S3AclParams {
  val name = "self-deploy"
  val documentation =
    """
      |This is a special deployment type that can be used to self-deploy Riff-Raff.
      |
      |The approach is to upload a file to S3 and then hit a switch on the singleton instance.
      |When the switch is hit it will shutdown with a specific exit code at the next
      |opportunity (when it has finished deploys).
    """.stripMargin

  val bucket = Param[String]("bucket",
    """
      |S3 bucket name to upload artifact into.
      |
      |The path in the bucket is `<stack>/<stage>/<packageName>/<fileName>`.
    """.stripMargin
  )

  val managementPort = Param[Int]("managementPort",
    "For deferred deployment only: The port of the management pages containing the location of the switchboard"
  ).default(18080)
  val managementProtocol = Param[String]("managementProtocol",
    "For deferred deployment only: The protocol of the management pages containing the location of the switchboard"
  ).default("http")
  val switchboardPath = Param[String]("switchboardPath",
    "For deferred deployment only: The URL path on the host to the switchboard management page"
  ).default("/management/switchboard")

  def perAppActions = {
    case "uploadArtifacts" => (pkg) => (lookup, parameters, stack) =>
      implicit val keyRing = lookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      List(
        S3Upload(
          stack,
          parameters.stage,
          bucket.get(pkg).orElse(stack.nameOption.map(stackName => s"$stackName-dist")).get,
          new File(pkg.srcDir.getPath + "/"),
          publicReadAcl = false
        )
      )
  }

  override def perHostActions = {
    case "selfDeploy" => pkg => (host, keyRing) => {
      implicit val key = keyRing
      ChangeSwitch(host, managementProtocol(pkg), managementPort(pkg), switchboardPath(pkg),
        "shutdown-when-inactive", desiredState=true) :: Nil
    }
  }
}

