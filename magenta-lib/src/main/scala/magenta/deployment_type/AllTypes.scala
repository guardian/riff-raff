package magenta.deployment_type

/** A canonical list of all deployment types supported by Riff-Raff.
  *
  * This list is used both by the main application (AppComponents) and by the
  * JSON Schema generator, so that the schema stays in sync with the code.
  */
object AllTypes {

  def allDeploymentTypes(tagger: BuildTags): Seq[DeploymentType] = Seq(
    S3,
    AutoScaling,
    Fastly,
    FastlyCompute,
    new CloudFormation(tagger),
    Lambda,
    LambdaLayer,
    AmiCloudFormationParameter,
    SelfDeploy
  )
}
