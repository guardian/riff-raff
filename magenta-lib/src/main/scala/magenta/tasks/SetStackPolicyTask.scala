package magenta.tasks
import magenta.tasks.StackPolicy.{accountPrivateTypes, allSensitiveResourceTypes, toPolicyDoc}
import magenta.{DeploymentResources, KeyRing, Region}
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.{ChangeSetType, ListTypesRequest, SetStackPolicyRequest, Visibility}

import scala.collection.JavaConverters.iterableAsScalaIterableConverter

sealed trait StackPolicy { def name: String = this.getClass.getSimpleName }
case object AllowAllPolicy extends StackPolicy
case object DenyReplaceDeletePolicy extends StackPolicy

object StackPolicy {

  def toPolicyDoc(policy: StackPolicy, sensitiveResourceTypes: Set[String], accountPrivateTypes: () => Set[String]): String = policy match {
    case AllowAllPolicy =>
      ALLOW_ALL_POLICY
    case DenyReplaceDeletePolicy =>
      val sensitiveResources = sensitiveResourceTypes.filter(t => t.startsWith("AWS") || accountPrivateTypes().contains(t))
      DENY_REPLACE_DELETE_POLICY(sensitiveResources)
  }

  def accountPrivateTypes(client: CloudFormationClient): Set[String] = {
    val request =  ListTypesRequest.builder().visibility(Visibility.PRIVATE).build()
    val response = client.listTypesPaginator(request)
    response.typeSummaries().asScala.map(_.typeName()).toSet
  }

  /** CFN resource types that have state or are likely to exist in external
   * config such as DNS or application config.
   */
  val allSensitiveResourceTypes: Set[String] =
    Set(
      // databases: RDS, DynamoDB, DocumentDB, Elastic
      "AWS::RDS::DBInstance",
      "AWS::DynamoDB::Table",
      "AWS::DocDB::DBInstance",
      "AWS::DocDB::DBCluster",
      "AWS::Elasticsearch::Domain",
      // queues/streams: SNS, SQS, Kinesis streams
      "AWS::SNS::Topic",
      "AWS::SQS::Queue",
      "AWS::Kinesis::Stream",
      // loadbalancers
      "AWS::ElasticLoadBalancing::LoadBalancer",
      "AWS::ElasticLoadBalancingV2::LoadBalancer",
      // cloudfront
      "AWS::CloudFront::Distribution",
      // API gateway
      "AWS::ApiGateway::RestApi",
      "AWS::ApiGateway::DomainName",
      // buckets (although we think they can't be deleted with content)
      "AWS::S3::Bucket",
      // DNS infrastructure
      "AWS::Route53::HostedZone",
      "AWS::Route53::RecordSet",
      "AWS::Route53::RecordSetGroup",
      // Private types
      "Guardian::DNS::RecordSet",
    )

  private[this] val ALLOW_ALL_POLICY =
    """{
      |  "Statement" : [
      |    {
      |      "Effect" : "Allow",
      |      "Action" : "Update:*",
      |      "Principal": "*",
      |      "Resource" : "*"
      |    }
      |  ]
      |}
      |""".stripMargin

  private[this] def DENY_REPLACE_DELETE_POLICY(sensitiveTypes: Set[String]): String =
      s"""{
         |  "Statement" : [
         |    {
         |      "Effect" : "Deny",
         |      "Action" : ["Update:Replace", "Update:Delete"],
         |      "Principal": "*",
         |      "Resource" : "*",
         |      "Condition" : {
         |        "StringEquals" : {
         |          "ResourceType" : [
         |            ${sensitiveTypes.mkString("\"","\",\n\"", "\"")}
         |          ]
         |        }
         |      }
         |    },
         |    {
         |      "Effect" : "Allow",
         |      "Action" : "Update:*",
         |      "Principal": "*",
         |      "Resource" : "*"
         |    }
         |  ]
         |}
         |""".stripMargin



  def toMarkdown(policy: StackPolicy): String = {
    s"""**${policy.name}**:
      |
      |```
      |${toPolicyDoc(policy, allSensitiveResourceTypes, () => allSensitiveResourceTypes)}
      |```
      |""".stripMargin
  }
}

class SetStackPolicyTask(
                          region: Region,
                          stackLookup: CloudFormationStackMetadata,
                          val stackPolicy: StackPolicy,
                          )(implicit val keyRing: KeyRing) extends Task {
  override def execute(resources: DeploymentResources, stopFlag: => Boolean): Unit = {
    CloudFormation.withCfnClient(keyRing, region, resources) { cfnClient =>
      val (stackName, changeSetType, _) = stackLookup.lookup(resources.reporter, cfnClient)
      val policyDoc = toPolicyDoc(stackPolicy, allSensitiveResourceTypes, () => accountPrivateTypes(cfnClient))

      changeSetType match {
        case ChangeSetType.CREATE => resources.reporter.info(s"Stack $stackName not found - no need to update policy")
        case _ =>
          resources.reporter.info(s"Setting update policy for stack $stackName to ${stackPolicy}")
          resources.reporter.info(s"Body of stack policy is: ${policyDoc}.")
          cfnClient.setStackPolicy(
            SetStackPolicyRequest.builder
              .stackName(stackName)
              .stackPolicyBody(policyDoc)
              .build()
          )
      }
    }
  }

  override def description: String = s"Set stack update policy to ${stackPolicy.name}"
}
