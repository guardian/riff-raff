package magenta.deployment_type

import magenta.tasks.S3.{Bucket, BucketByAuto, BucketByName}
import magenta.{DeployReporter, DeployTarget, DeploymentPackage, Region}

object AutoDistBucket {
  val BUCKET_PREFIX = "riff-raff-artifact-bucket"
}

trait AutoDistBucketParameters {
  this: DeploymentType =>

  val bucketParam = Param[String]("bucket",
    documentation =
      """
        |Name of the S3 bucket where the distribution artifacts should be uploaded.
      """.stripMargin
  )

  val autoDistBucketParam = Param[Boolean]("autoDistBucket",
    """Use an automatically created dist bucket (that is unique per account/region) to upload the
      |distribution artifacts to. This should be used in conjunction with `autoDistBucketParameter`
      |on the `cloud-formation` deployment type.""".stripMargin
  ).default(false)

  def resolveBucket(pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter): Bucket = {
    val useAutoDistBucket = autoDistBucketParam(pkg, target, reporter)
    val maybeExplicitBucket = bucketParam.get(pkg)
    (useAutoDistBucket, maybeExplicitBucket) match {
      case (true, None) => BucketByAuto(AutoDistBucket.BUCKET_PREFIX, target.region)
      case (false, Some(explicitBucket)) => BucketByName(explicitBucket)
      case _ => reporter.fail("One and only one of the following must be set: the bucket parameter or autoDistBucket=true")
    }
  }
}
