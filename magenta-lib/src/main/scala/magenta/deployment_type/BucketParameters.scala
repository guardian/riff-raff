package magenta.deployment_type

import magenta.tasks.S3.{Bucket, BucketByName, BucketBySsmKey}
import magenta.{DeployReporter, DeployTarget, DeploymentPackage}

trait BucketParameters {
  this: DeploymentType =>

  import BucketParametersDefaults._

  val bucketParam = Param[String](
    "bucket",
    documentation = """
        |Name of the S3 bucket where the distribution artifacts should be uploaded.
      """.stripMargin,
    optional = true
  )

  val bucketSsmLookupParam = Param[Boolean](
    "bucketSsmLookup",
    """Lookup the bucket for uploading distribution artifacts from SSM in the target account and region. This is
      |designed to be used in conjunction with a matching `AWS::SSM::Parameter::Value<String>` CFN parameter
      |on any related CloudFormation template.""".stripMargin
  ).default(bucketSsmLookupParamDefault)

  val bucketSsmKeyParam = Param[String](
    "bucketSsmKey",
    """The SSM key used to lookup the bucket name for uploading distribution artifacts.""".stripMargin
  ).default(defaultSsmKeyParamDefault)

  def getTargetBucketFromConfig(
      pkg: DeploymentPackage,
      target: DeployTarget,
      reporter: DeployReporter
  ): Bucket = {
    val bucketSsmLookup = bucketSsmLookupParam(pkg, target, reporter)
    val maybeExplicitBucket = bucketParam.get(pkg)

    val bucket = (bucketSsmLookup, maybeExplicitBucket) match {
      // Default to looking up target bucket from SSM
      case (_, None) =>
        BucketBySsmKey(bucketSsmKeyParam(pkg, target, reporter))
      case (false, Some(explicitBucket)) =>
        reporter.warning(
          "Explicit bucket name in riff-raff.yaml. Prefer to use bucketSsmLookup=true, removing private information from VCS."
        )
        BucketByName(explicitBucket)
      case (true, Some(explicitBucket)) =>
        reporter.fail(
          s"Bucket name provided ($explicitBucket) & bucketSsmLookup=true, please choose one or omit both to default to SSM lookup."
        )
    }
    reporter.verbose(s"Resolved artifact bucket as $bucket")
    bucket
  }
}

object BucketParametersDefaults {
  val bucketSsmLookupParamDefault = false
  val defaultSsmKeyParamDefault = "/account/services/artifact.bucket"
}
