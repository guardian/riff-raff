package magenta.deployment_type

import magenta.tasks.ASG.{TagAbsent, TagExists, TagMatch, TagRequirement}
import magenta.{
  App,
  DeployTarget,
  DeploymentPackage,
  DeploymentResources,
  KeyRing,
  Region,
  Stack,
  Stage
}
import magenta.tasks._
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup
import magenta.tasks.{S3 => S3Tasks}
import software.amazon.awssdk.services.ssm.SsmClient

import java.time.Duration
import java.time.Duration.{ofMinutes, ofSeconds}

sealed trait MigrationTagRequirements
case object NoMigration extends MigrationTagRequirements
case object MustBePresent extends MigrationTagRequirements
case object MustNotBePresent extends MigrationTagRequirements

case class AutoScalingGroupInfo(
    asg: AutoScalingGroup,
    tagRequirements: List[TagRequirement]
)

object AutoScalingGroupLookup {
  def getTagRequirements(
      stage: Stage,
      stack: Stack,
      app: App,
      migrationTagRequirements: MigrationTagRequirements
  ): List[TagRequirement] = {
    val migrationRequirement: Option[TagRequirement] =
      migrationTagRequirements match {
        case NoMigration      => None
        case MustBePresent    => Some(TagExists("gu:riffraff:new-asg"))
        case MustNotBePresent => Some(TagAbsent("gu:riffraff:new-asg"))
      }
    List(
      TagMatch("Stage", stage.name),
      TagMatch("Stack", stack.name),
      TagMatch("App", app.name)
    ) ++ migrationRequirement
  }

  def getTargetAsg(
      keyRing: KeyRing,
      target: DeployTarget,
      migrationTagRequirements: MigrationTagRequirements,
      resources: DeploymentResources,
      pkg: DeploymentPackage
  ) = {
    ASG.withAsgClient[Option[AutoScalingGroupInfo]](
      keyRing,
      target.region,
      resources
    ) { asgClient =>
      val tagRequirements = getTagRequirements(
        target.parameters.stage,
        target.stack,
        pkg.app,
        migrationTagRequirements
      )
      ASG
        .groupWithTags(
          tagRequirements,
          asgClient,
          resources.reporter,
          strategy = target.parameters.updateStrategy
        )
        .collect(AutoScalingGroupInfo(_, tagRequirements))
    }
  }
}

object AutoScaling extends DeploymentType with BucketParameters {
  val name = "autoscaling"
  val documentation =
    """
      |Deploy to an autoscaling group in AWS.
      |
      |The approach of this deploy type is to:
      |
      | - upload a new application artifact to an S3 bucket (from which new instances download their application)
      | - scale up, wait for the new instances to become healthy and then scale back down
    """.stripMargin

  val secondsToWait: Param[Duration] = Param
    .waitingSecondsFor("secondsToWait", "instances to enter service")
    .default(ofMinutes(15))
  val healthcheckGrace: Param[Duration] = Param
    .waitingSecondsFor("healthcheckGrace", "the AWS api to stabilise")
    .default(ofSeconds(20))
  val warmupGrace: Param[Duration] = Param
    .waitingSecondsFor(
      "warmupGrace",
      "the instances in the load balancer to warm up"
    )
    .default(ofSeconds(1))
  val terminationGrace: Param[Duration] = Param
    .waitingSecondsFor(
      "terminationGrace",
      "the AWS api to stabilise after instance termination"
    )
    .default(ofSeconds(10))

  val prefixStage = Param[Boolean](
    "prefixStage",
    documentation = "Whether to prefix `stage` to the S3 location"
  ).default(true)
  val prefixPackage = Param[Boolean](
    "prefixPackage",
    documentation = "Whether to prefix `package` to the S3 location"
  ).default(true)
  val prefixStack = Param[Boolean](
    "prefixStack",
    documentation = "Whether to prefix `stack` to the S3 location"
  ).default(true)

  val prefixApp = Param[Boolean](
    name = "prefixApp",
    documentation = """
        |Whether to prefix `app` to the S3 location instead of `package`.
        |
        |When `true` `prefixPackage` will be ignored and `app` will be used over `package`, useful if `package` and `app` don't align.
        |""".stripMargin
  ).default(false)

  val publicReadAcl = Param[Boolean](
    "publicReadAcl",
    "Whether the uploaded artifacts should be given the PublicRead Canned ACL"
  ).default(false)

  val asgMigrationInProgress = Param[Boolean](
    "asgMigrationInProgress",
    """
      |When this is set to true, Riff-Raff will search for two autoscaling groups and deploy to them both.
      |
      |Note, for this to work, the following need to hold:
      |
      | - both ASGs need matching Stack,Stage,App tags
      | - *exactly* two matching ASGs (not 1, 3, etc.)
      | - the new ASG needs to have a 'gu:riffraff:new-asg' tag on it (with any value)""".stripMargin
  ).default(false)

  val deploy = Action(
    "deploy",
    """
      |Carries out the update of instances in an autoscaling group. We carry out the following tasks:
      | - tag existing instances in the ASG with a termination tag
      | - double the size of the auto-scaling group (new instances will have the new application)
      | - wait for the new instances to enter service
      | - terminate previously tagged instances
      |
      |The action checks whether the auto-scaling group maxsize is big enough before starting the process and also
      |suspends and resumes cloud watch alarms in order to prevent false alarms.
      |
      |There are some delays introduced in order to work around consistency issues in the AWS ASG APIs.
    """.stripMargin
  ) { (pkg, resources, target) =>
    implicit val keyRing = resources.assembleKeyring(target, pkg)
    val reporter = resources.reporter
    def tasksPerAutoScalingGroup(
        autoScalingGroup: AutoScalingGroupInfo
    ): List[ASGTask] = {
      List(
        WaitForStabilization(autoScalingGroup, ofMinutes(5), target.region),
        CheckGroupSize(autoScalingGroup, target.region),
        SuspendAlarmNotifications(autoScalingGroup, target.region),
        TagCurrentInstancesWithTerminationTag(autoScalingGroup, target.region),
        ProtectCurrentInstances(autoScalingGroup, target.region),
        DoubleSize(autoScalingGroup, target.region),
        HealthcheckGrace(
          autoScalingGroup,
          target.region,
          healthcheckGrace(pkg, target, reporter)
        ),
        WaitForStabilization(
          autoScalingGroup,
          secondsToWait(pkg, target, reporter),
          target.region
        ),
        WarmupGrace(
          autoScalingGroup,
          target.region,
          warmupGrace(pkg, target, reporter)
        ),
        WaitForStabilization(
          autoScalingGroup,
          secondsToWait(pkg, target, reporter),
          target.region
        ),
        CullInstancesWithTerminationTag(autoScalingGroup, target.region),
        TerminationGrace(
          autoScalingGroup,
          target.region,
          terminationGrace(pkg, target, reporter)
        ),
        WaitForStabilization(
          autoScalingGroup,
          secondsToWait(pkg, target, reporter),
          target.region
        ),
        ResumeAlarmNotifications(autoScalingGroup, target.region)
      )
    }
    val groupsToUpdate: List[AutoScalingGroupInfo] =
      if (asgMigrationInProgress(pkg, target, reporter)) {
        List(
          AutoScalingGroupLookup.getTargetAsg(
            keyRing,
            target,
            MustNotBePresent,
            resources,
            pkg
          ),
          AutoScalingGroupLookup.getTargetAsg(
            keyRing,
            target,
            MustBePresent,
            resources,
            pkg
          )
        ).flatten
      } else {
        List(
          AutoScalingGroupLookup.getTargetAsg(
            keyRing,
            target,
            NoMigration,
            resources,
            pkg
          )
        ).flatten
      }
    groupsToUpdate.flatMap(asg => tasksPerAutoScalingGroup(asg))
  }

  // TODO this is copied from `Lambda.scala` and could be DRYed out
  def withSsm[T](
      keyRing: KeyRing,
      region: Region,
      resources: DeploymentResources
  ): (SsmClient => T) => T = SSM.withSsmClient[T](keyRing, region, resources)

  val uploadArtifacts = Action(
    "uploadArtifacts",
    """
      |Uploads the files in the deployment's directory to the specified bucket.
    """.stripMargin
  ) { (pkg, resources, target) =>
    implicit val keyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient = resources.artifactClient
    val reporter = resources.reporter

    val maybePackageOrAppName: Option[String] = (
      prefixPackage(pkg, target, reporter),
      prefixApp(pkg, target, reporter)
    ) match {
      case (_, true)      => Some(pkg.app.name)
      case (true, false)  => Some(pkg.name)
      case (false, false) => None
    }

    val prefix = S3Upload.prefixGenerator(
      stack =
        if (prefixStack(pkg, target, reporter)) Some(target.stack) else None,
      stage =
        if (prefixStage(pkg, target, reporter)) Some(target.parameters.stage)
        else None,
      packageOrAppName = maybePackageOrAppName
    )

    val bucket = getTargetBucketFromConfig(pkg, target, reporter)

    val s3Bucket = S3Tasks.getBucketName(
      bucket,
      withSsm(keyRing, target.region, resources),
      resources.reporter
    )

    List(
      S3Upload(
        target.region,
        s3Bucket,
        Seq(pkg.s3Package -> prefix),
        publicReadAcl = publicReadAcl(pkg, target, reporter)
      )
    )
  }

  val defaultActions = List(uploadArtifacts, deploy)
}
