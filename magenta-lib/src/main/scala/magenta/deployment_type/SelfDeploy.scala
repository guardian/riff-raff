package magenta.deployment_type

import magenta.tasks.{S3Upload, ShutdownTask}

object SelfDeploy extends DeploymentType {
  val name = "self-deploy"
  val documentation =
    """
      |This is a special deployment type that can be used to self-deploy Riff-Raff.
      |
      |The approach is to upload a file to S3 and then hit a switch on the singleton instance.
      |When the switch is hit it will shutdown with a specific exit code at the next
      |opportunity (when it has finished deploys).
    """.stripMargin

  val bucket = Param[String](
    "bucket",
    """
      |S3 bucket name to upload artifact into.
      |
      |The path in the bucket is `<stack>/<stage>/<packageName>/<fileName>`.
      |
      |Despite there being a default for this we are migrating to always requiring it to be specified.
    """.stripMargin
  ).defaultFromContext { case (_, target) =>
    Right(s"${target.stack.name}-dist")
  }

  val publicReadAcl = Param[Boolean](
    "publicReadAcl",
    "Whether the uploaded artifacts should be given the PublicRead Canned ACL. (Default is true!)"
  ).default(false)

  val uploadArtifacts = Action(
    "uploadArtifacts",
    """
      |Uploads the files in the deployment's directory to the specified bucket.
    """.stripMargin
  ) { (pkg, resources, target) =>
    implicit val keyRing = resources.assembleKeyring(target, pkg)
    val reporter = resources.reporter
    implicit val artifactClient = resources.artifactClient
    val prefix =
      S3Upload.prefixGenerator(target.stack, target.parameters.stage, pkg.name)
    List(
      S3Upload(
        target.region,
        bucket(pkg, target, reporter),
        paths = Seq(pkg.s3Package -> prefix)
      )
    )
  }

  val selfDeploy = Action(
    "selfDeploy",
    """
      |Invokes the Shutdown controller on the target instance.
    """.stripMargin
  ) { (pkg, resources, target) =>
    implicit val keyRing = resources.assembleKeyring(target, pkg)

    val hosts = resources.lookup.hosts
      .get(pkg, pkg.app, target.parameters, target.stack)
      .toList
    hosts.map(ShutdownTask.apply)
  }

  def defaultActions = List(uploadArtifacts, selfDeploy)
}
