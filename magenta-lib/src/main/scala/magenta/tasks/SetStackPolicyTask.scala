package magenta.tasks
import magenta.tasks.StackPolicy.{
  accountResourceTypes,
  allSensitiveResourceTypes,
  toPolicyDoc
}
import magenta.{DeploymentResources, KeyRing, Region}
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.{
  Category,
  ChangeSetType,
  ListTypesRequest,
  RegistryType,
  SetStackPolicyRequest,
  TypeFilters,
  Visibility
}

import scala.jdk.CollectionConverters._

sealed trait StackPolicy { def name: String = this.getClass.getSimpleName }
case object AllowAllPolicy extends StackPolicy
case object DenyReplaceDeletePolicy extends StackPolicy

object StackPolicy {

  def toPolicyDoc(
      policy: StackPolicy,
      accountResourceTypes: () => Set[String]
  ): String = policy match {
    case AllowAllPolicy => ALLOW_ALL_POLICY
    case DenyReplaceDeletePolicy =>
      DENY_REPLACE_DELETE_POLICY(accountResourceTypes())
  }

  /** Returns the names of private resource types that can be CloudFormed in a
    * given region. Necessary because we've not deployed all our private
    * resource types to all regions.
    *
    * @param client
    *   A CloudFormation client, with a set region
    * @return
    *   A Set of Resource type names
    * @see
    *   https://github.com/guardian/cfn-private-resource-types
    */
  private def accountPrivateTypes(client: CloudFormationClient): Set[String] = {
    val request =
      ListTypesRequest.builder().visibility(Visibility.PRIVATE).build()
    val response = client.listTypesPaginator(request)
    response.typeSummaries().asScala.map(_.typeName()).toSet
  }

  /** Returns the names of AWS and private resource types that can be
    * CloudFormed in a given region. Necessary because every region does not
    * support every resource. For example AWS::DocDB::DBCluster is not supported
    * in us-west-1.
    *
    * @param client
    *   A CloudFormation client, with a set region
    * @return
    *   A Set of Resource type names
    * @see
    *   https://awscli.amazonaws.com/v2/documentation/api/latest/reference/cloudformation/list-types.html
    * @see
    *   https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/cfn-resource-specification.html
    */
  private def accountAwsResourceTypes(
      client: CloudFormationClient
  ): Set[String] = {
    val request = ListTypesRequest
      .builder()
      .visibility(Visibility.PUBLIC)
      .`type`(RegistryType.RESOURCE)
      .filters(
        TypeFilters
          .builder()
          .category(Category.AWS_TYPES)
          .build()
      )
      .build()
    val response = client.listTypesPaginator(request)
    response.typeSummaries().asScala.map(_.typeName()).toSet
  }

  /** Returns the names of AWS and Private resource types that can be
    * CloudFormed in a given region.
    *
    * @param client
    *   A CloudFormation client, with a set region
    * @return
    *   A Set of Resource type names
    */
  def accountResourceTypes(client: CloudFormationClient): Set[String] = {
    accountAwsResourceTypes(client) ++ accountPrivateTypes(client)
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
      "AWS::ApiGateway::BasePathMapping",
      "AWS::ApiGateway::Stage",
      // buckets (although we think they can't be deleted with content)
      "AWS::S3::Bucket",
      // DNS infrastructure
      "AWS::Route53::HostedZone",
      "AWS::Route53::RecordSet",
      "AWS::Route53::RecordSetGroup",
      // VPC
      "AWS::EC2::EIP",
      "AWS::EC2::InternetGateway",
      "AWS::EC2::NatGateway",
      "AWS::EC2::Route",
      "AWS::EC2::RouteTable",
      "AWS::EC2::Subnet",
      "AWS::EC2::SubnetRouteTableAssociation",
      "AWS::EC2::VPC",
      "AWS::EC2::VPCEndpoint",
      "AWS::EC2::VPCGatewayAttachment",
      // Storage that persists outside of EC2 life-cycle
      "AWS::EFS::FileSystem",
      // Private types
      "Guardian::DNS::RecordSet",
      // Nested stacks, which may contain any of the above!
      "AWS::CloudFormation::Stack"
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

  private[this] def DENY_REPLACE_DELETE_POLICY(
      availableAccountResources: Set[String]
  ): String = {
    val resourcesToProtect = allSensitiveResourceTypes
      .intersect(availableAccountResources)
      .toSeq
      .sorted // alphabetical to make testing easier

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
         |            ${resourcesToProtect.mkString("\"", "\",\"", "\"")}
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
  }

  def toMarkdown(policy: StackPolicy): String = {
    s"""**${policy.name}**:
      |
      |```
      |${toPolicyDoc(policy, () => allSensitiveResourceTypes)}
      |```
      |""".stripMargin
  }
}

class SetStackPolicyTask(
    region: Region,
    stackLookup: CloudFormationStackMetadata,
    val stackPolicy: StackPolicy
)(implicit val keyRing: KeyRing)
    extends Task {
  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    CloudFormation.withCfnClient(keyRing, region, resources) { cfnClient =>
      val (stackName, changeSetType, _, _) =
        stackLookup.lookup(resources.reporter, cfnClient)
      val policyDoc =
        toPolicyDoc(stackPolicy, () => accountResourceTypes(cfnClient))

      changeSetType match {
        case ChangeSetType.CREATE =>
          resources.reporter.info(
            s"Stack $stackName not found - no need to update policy"
          )
        case _ =>
          resources.reporter.info(
            s"Setting update policy for stack $stackName to ${stackPolicy}"
          )
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

  override def description: String =
    s"Set stack update policy to ${stackPolicy.name}"
}
