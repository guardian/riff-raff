package magenta.deployment_type

import magenta.tasks.S3.{Bucket, BucketByName, BucketBySsmKey}
import magenta.{DeployReporter, DeployTarget, DeploymentPackage}

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

  def getTargetBucketFromConfig(pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter): Bucket = {
    val bucketSsmLookup = bucketSsmLookupParam(pkg, target, reporter)
    val maybeExplicitBucket = bucketParam.get(pkg)

    val bucket = (bucketSsmLookup, maybeExplicitBucket) match {
      case (true, None) => BucketBySsmKey(bucketSsmKeyParam(pkg, target, reporter))
      case (false, Some(explicitBucket)) => BucketByName(explicitBucket)
      case _ => reporter.fail("One and only one of the following must be set: the bucket parameter or bucketSsmLookup=true")
    }
    reporter.verbose(s"Resolved artifact bucket as $bucket")
    bucket
  }
}
