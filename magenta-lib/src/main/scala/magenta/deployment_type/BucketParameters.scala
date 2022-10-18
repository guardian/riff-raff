package magenta.deployment_type

import magenta.deployment_type.S3.bucketResource
import magenta.tasks.S3.{Bucket, BucketByName, BucketBySsmKey}
import magenta.{Datum, DeployReporter, DeployTarget, DeploymentPackage, DeploymentResources}

trait BucketParameters {
  this: DeploymentType =>

  val bucketParam = Param[String]("bucket",
    documentation =
      """
        |Name of the S3 bucket where the distribution artifacts should be uploaded.
      """.stripMargin,
    optional = true
  )

  val bucketSsmLookupParam = Param[Boolean]("bucketSsmLookup",
    """Lookup the bucket for uploading distribution artifacts from SSM in the target account and region. This is
      |designed to be used in conjunction with a matching `AWS::SSM::Parameter::Value<String>` CFN parameter
      |on any related CloudFormation template.""".stripMargin
  ).default(false)

  val bucketSsmKeyParam = Param[String]("bucketSsmKey",
    """The SSM key used to lookup the bucket name for uploading distribution artifacts.""".stripMargin
  ).default("/account/services/artifact.bucket")

  // legacy - better to use SSM to store the bucket name rather than rely on riffraff resources - see BucketParameters
  val bucketResource = Param[String]("bucketResource",
    """Deploy Info resource key to use to look up the S3 bucket to which the package files should be uploaded.
      |
      |This parameter is mutually exclusive with `bucket`, which can be used instead if you upload to the same bucket
      |regardless of the target stage.
    """.stripMargin,
    optional = true
  )

  def resourceLookupFor(resourceName: String, pkg: DeploymentPackage, target: DeployTarget, resources: DeploymentResources): Option[Datum] = {
      val dataLookup = resources.lookup.data
      val datumOpt = dataLookup.datum(resourceName, pkg.app, target.parameters.stage, target.stack)
      if (datumOpt.isEmpty) {
        def str(f: Datum => String) = s"[${dataLookup.get(resourceName).map(f).toSet.mkString(", ")}]"
        resources.reporter.verbose(s"No datum found for resource=$resourceName app=${pkg.app} stage=${target.parameters.stage} stack=${target.stack} - values *are* defined for app=${str(_.app)} stage=${str(_.stage)} stack=${str(_.stack.mkString)}")
      }
      datumOpt
  }

  def getTargetBucketFromConfig(pkg: DeploymentPackage, target: DeployTarget, resources: DeploymentResources, keyRequired: Boolean = false): Bucket = {
    val bucketSsmLookup = bucketSsmLookupParam(pkg, target, resources.reporter)
    val maybeExplicitBucket = bucketParam.get(pkg)
    val maybeBucketResource = bucketResource.get(pkg)

    val bucket = (bucketSsmLookup, maybeExplicitBucket, maybeBucketResource) match {
      case (true, None, None) =>
        if (keyRequired) {
          assert(bucketSsmKeyParam.get(pkg).isDefined, s"${bucketSsmKeyParam.name} is required for this deploy type")
          BucketBySsmKey(bucketSsmKeyParam(pkg, target, resources.reporter))
        } else
          BucketBySsmKey(bucketSsmKeyParam(pkg, target, resources.reporter))
      case (false, Some(explicitBucket), None) =>
        resources.reporter.warning(s"Explicit bucket name in riff-raff.yaml. Prefer to use ${bucketSsmLookupParam.name}=true, removing private information from VCS.")
        BucketByName(explicitBucket)
      case (false, None, Some(resourceName)) =>
        val data = resourceLookupFor(resourceName, pkg, target, resources)
        assert(data.isDefined, s"Cannot find resource value for ${bucketResource(pkg, target, resources.reporter)} (${pkg.app} in ${target.parameters.stage.name})")
        BucketByName(data.get.value)
      case _ => resources.reporter.fail(s"One and only one of the following must be set: the ${bucketParam.name}, ${bucketResource.name}  or ${bucketSsmLookupParam.name}=true")
    }
    resources.reporter.verbose(s"Resolved artifact bucket as $bucket")
    bucket
  }
}
