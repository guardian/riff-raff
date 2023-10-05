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

  val bucketSsmKeyStageParam = Param[Map[String, String]](
    "bucketSsmKeyStageParam",
    """
      |Like bucketSsmKeyParam, but with the ability to configure by stage:
      |
      |```yaml
      |  CODE: some-ssm-path-for-code
      |  PROD: some-ssm-path-for-prod
      |```
      |
      |For the most part, you should not use this. But it is useful if you
      |are e.g. serving static sites from S3.
      |""".stripMargin
  ).default(Map.empty)

  def getTargetBucketFromConfig(
      pkg: DeploymentPackage,
      target: DeployTarget,
      reporter: DeployReporter
  ): Bucket = {

    def bySsm(): Bucket = {
      val stage = target.parameters.stage.name
      val ssmKeyByStage = bucketSsmKeyStageParam(pkg, target, reporter)
      val stageKey = ssmKeyByStage.get(stage)

      if (ssmKeyByStage.nonEmpty && stageKey.isEmpty) {
        reporter.fail(
          s"Unable to determine bucket to deploy to: bucketSsmKeyStageParam is set but no mapping was found for stage '$stage'."
        )
      }

      BucketBySsmKey(
        stageKey.getOrElse(bucketSsmKeyParam(pkg, target, reporter))
      )
    }

    val bucketSsmLookup = bucketSsmLookupParam(pkg, target, reporter)
    val explicitBucket = bucketParam.get(pkg)

    // The behaviour here is *very* counter-intuitive; even if bucketSsmLookup=false
    // we default to SSM unless an explicit bucket name has been set.
    val bucket = (bucketSsmLookup, explicitBucket) match {
      case (true, Some(_)) =>
        reporter.fail(
          s"Bucket name provided & bucketSsmLookup=true, please choose one or omit both to default to SSM lookup."
        )
      case (false, Some(name)) =>
        reporter.warning(
          "riff-raff.yaml exposes the bucket name. Prefer to use bucketSsmLookup=true and store bucket name in parameter store, removing private information from version control."
        )
        BucketByName(name)
      case (_, None) =>
        bySsm()
    }

    reporter.verbose(s"Resolved artifact bucket as $bucket")
    bucket
  }
}

object BucketParametersDefaults {
  val bucketSsmLookupParamDefault = false
  val defaultSsmKeyParamDefault = "/account/services/artifact.bucket"
}
