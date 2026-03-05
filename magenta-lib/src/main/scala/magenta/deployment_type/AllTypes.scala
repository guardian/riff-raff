package magenta.deployment_type

/** A canonical list of all deployment types supported by Riff-Raff.
  *
  * This list is used both by the main application (AppComponents) and by the
  * JSON Schema generator, so that the schema stays in sync with the code.
  *
  * CloudFormation requires a BuildTags instance. For contexts where build tag
  * resolution is not needed (e.g. schema generation), pass NoopBuildTags.
  */
object AllTypes {

  /** A BuildTags implementation that returns no tags. Suitable for contexts
    * where no runtime build information is available (schema generation,
    * tests).
    */
  object NoopBuildTags extends BuildTags {
    def get(projectName: String, buildId: String): Map[String, String] =
      Map.empty
  }

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

  /** All deployment types using a no-op BuildTags. Use this when you only need
    * metadata (names, params, actions) and not runtime behaviour.
    */
  lazy val allDeploymentTypesForSchema: Seq[DeploymentType] =
    allDeploymentTypes(NoopBuildTags)
}
