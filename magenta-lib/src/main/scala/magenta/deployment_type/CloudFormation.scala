package magenta.deployment_type

import java.net.URLEncoder
import java.util.UUID

import magenta.artifact.S3Path
import magenta.deployment_type.CloudFormationDeploymentTypeParameters._
import magenta.tasks.UpdateCloudFormationTask.LookupByTags
import magenta.tasks._
import org.joda.time.DateTime

object CloudFormation extends DeploymentType with CloudFormationDeploymentTypeParameters {

  val name = "cloud-formation"
  def documentation =
    """Update an AWS CloudFormation template by creating and executing a change set.
      |
      |This type will populate the following parameters in a cloudformation template:
      |  - Stage (e.g. `CODE` or `PROD`)
      |  - Stack (e.g. `deploy` or `frontend`)
      |  - BuildId (i.e. the number of the build such as `543`)
      |
      |NOTE: It is strongly recommended you do _NOT_ set a desired-capacity on auto-scaling groups, managed
      |with CloudFormation templates deployed in this way, as otherwise any deployment will reset the
      |capacity to this number, even if scaling actions have triggered, changing the capacity, in the
      |mean-time. Alternatively, you will need to add this as a dependency to your autoscaling deploy
      |in your riff-raff.yaml.
      |
      |Note that if you are using the riff-raff.yaml configuration format or if your template is over 51,200 bytes then
      |this task relies on a bucket in your account called `riff-raff-cfn-templates-<accountNumber>-<region>`. If it
      |doesn't exist Riff-Raff will try to create it (it will need permissions to call S3 create bucket and STS get
      |caller identity. If you don't want this to happen then then you are welcome to create it yourself. Riff-Raff will
      |create it with a lifecycle rule that deletes objects after one day. Templates over 51,200 bytes will be uploaded
      |to this bucket and sent to CloudFormation using the template URL parameter.
      |
      |In addition to any permissions required in order to create, modify and delete the resources within the template
      |itself the Riff-Raff user in your account must have the following permissions to create and execute changes:
      |
      |  - `cloudformation:CreateChangeSet`
      |  - `cloudformation:DescribeStacks`
      |  - `cloudformation:DescribeStackEvents`
      |  - `cloudformation:DescribeChangeSet`
      |  - `cloudformation:ExecuteChangeSet`
      |  - `cloudformation:DeleteChangeSet`
      |  - `cloudformation:CreateStack` (if `createStackIfAbsent` has not been manually set to false in riff-raff.yaml)
      |
    """.stripMargin

  val templatePath = Param[String]("templatePath",
    documentation = "Location of template to use within package."
  ).default("""cloud-formation/cfn.json""")

  val templateParameters = Param[Map[String, String]]("templateParameters",
    documentation = "Map of parameter names and values to be passed into template. `Stage` and `Stack` (if `defaultStacks` are specified) will be appropriately set automatically."
  ).default(Map.empty)

  val templateStageParameters = Param[Map[String, Map[String, String]]]("templateStageParameters",
    documentation =
      """Like templateParameters, a map of parameter names and values, but in this case keyed by stage to
        |support stage-specific configuration. E.g.
        |
        |    {
        |        "CODE": { "apiUrl": "my.code.endpoint", ... },
        |        "PROD": { "apiUrl": "my.prod.endpoint", ... },
        |    }
        |
        |At deploy time, parameters for the matching stage (if found) are merged into any
        |templateParameters parameters, with stage-specific values overriding general parameters
        |when in conflict.""".stripMargin
  ).default(Map.empty)

  val createStackIfAbsent = Param[Boolean]("createStackIfAbsent",
    documentation = "If set to true then the cloudformation stack will be created if it doesn't already exist"
  ).default(true)

  val secondsToWaitForChangeSetCreation = Param("secondsToWaitForChangeSetCreation",
    "Number of seconds to wait for the change set to be created").default(15 * 60)

  val updateStack = Action("updateStack",
    """
      |Apply the specified template to a cloudformation stack. This action runs an asynchronous update task and then
      |runs another task that _tails_ the stack update events (as well as possible).
      |
    """.stripMargin
  ){ (pkg, resources, target) => {
    implicit val keyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient = resources.artifactClient
    val reporter = resources.reporter

    val amiParameterMap: Map[CfnParam, TagCriteria] = getAmiParameterMap(pkg, target, reporter)
    val cloudFormationStackLookupStrategy = getCloudFormationStackLookupStrategy(pkg, target, reporter)

    val globalParams = templateParameters(pkg, target, reporter)
    val stageParams = templateStageParameters(pkg, target, reporter).lift.apply(target.parameters.stage.name).getOrElse(Map())

    val userParams = globalParams ++ stageParams
    val amiLookupFn = getLatestAmi(pkg, target, reporter, resources.lookup)

    val stackTags = cloudFormationStackLookupStrategy match {
      case LookupByTags(tags) => Some(tags)
      case _ => None
    }

    val changeSetName = s"${target.stack.name}-${new DateTime().getMillis}"

    val unresolvedParameters = new CloudFormationParameters(target, stackTags, userParams, amiParameterMap, amiLookupFn)

    val createNewStack = createStackIfAbsent(pkg, target, reporter)
    val stackLookup = new CloudFormationStackMetadata(cloudFormationStackLookupStrategy, changeSetName, createNewStack)

    List(
      new CreateChangeSetTask(
        target.region,
        templatePath = S3Path(pkg.s3Package, templatePath(pkg, target, reporter)),
        stackLookup,
        unresolvedParameters
      ),
      new CheckChangeSetCreatedTask(
        target.region,
        stackLookup,
        secondsToWaitForChangeSetCreation(pkg, target, reporter) * 1000
      ),
      new ExecuteChangeSetTask(
        target.region,
        stackLookup
      ),
      new CheckUpdateEventsTask(
        target.region,
        cloudFormationStackLookupStrategy
      ),
      new DeleteChangeSetTask(
        target.region,
        stackLookup
      )
    )
  }
  }

  def defaultActions = List(updateStack)
}
