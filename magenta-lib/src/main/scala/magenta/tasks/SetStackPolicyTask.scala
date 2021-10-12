package magenta.tasks
import magenta.Strategy.{Dangerous, MostlyHarmless}
import magenta.tasks.CloudFormation.withCfnClient
import magenta.tasks.StackPolicy.privateSensitiveResourceTypes
import magenta.{DeploymentResources, KeyRing, Region, Strategy}
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.{ChangeSetType, ListTypesRequest, SetStackPolicyRequest, Visibility}

import scala.collection.JavaConverters.asScalaBufferConverter

case class StackPolicy(name: String, body: String)

object StackPolicy {

  val ALLOW_ALL_POLICY: StackPolicy = StackPolicy("AllowAll",
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
  )

  def privateSensitiveResourceTypes(client: CloudFormationClient): Set[String] = {
    val request =  ListTypesRequest.builder().visibility(Visibility.PRIVATE).build()
    val response = client.listTypes(request)
    val privateTypes = response.typeSummaries().asScala.map(_.typeName()).toSet
    privateTypes.intersect(privateSensitiveResourceTypes)
  }

  // CFN resource types that have state or are likely to exist in
  // external config such as DNS or application config
  val sensitiveResourceTypes: List[String] = {
  List(
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
    "AWS::Route53::RecordSetGroup"
  )
  }

  val privateSensitiveResourceTypes: Set[String] = {
    Set(
      "Guardian::DNS::RecordSet"
    )
  }

  // Provide

  /** Policy to prevent destructive updates to stateful resources.
    *
    * @param privateSensitiveTypes private types that are defined for the target
    * AWS account. We want to protect these resources, but the stack policy will
    * fail if resources are specified which don't exist on the target account.
    */
  def DENY_REPLACE_DELETE_POLICY(privateSensitiveTypes: Set[String]): StackPolicy = {
    StackPolicy("DenyReplaceDelete",
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
         |            ${sensitiveResourceTypes.++(privateSensitiveTypes.toList).mkString("\"","\",\n\"", "\"")}
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
    )
  }

  def toMarkdown(policy: StackPolicy): String = {
    s"""**${policy.name}**:
      |
      |```
      |${policy.body}
      |```
      |""".stripMargin
  }
}

class SetStackPolicyTask(
                          region: Region,
                          stackLookup: CloudFormationStackMetadata,
                          val updateStrategy: Strategy = MostlyHarmless,
                          )(implicit val keyRing: KeyRing) extends Task {
  override def execute(resources: DeploymentResources, stopFlag: => Boolean): Unit = {
    CloudFormation.withCfnClient(keyRing, region, resources) { cfnClient =>
      val (stackName, changeSetType, _) = stackLookup.lookup(resources.reporter, cfnClient)

      val stackPolicy = updateStrategy match {
        case MostlyHarmless =>
          withCfnClient(keyRing, region, resources) { cfnClient =>
            val privateTypes = privateSensitiveResourceTypes(cfnClient)
            StackPolicy.DENY_REPLACE_DELETE_POLICY(privateTypes)
          }

        case Dangerous => StackPolicy.ALLOW_ALL_POLICY
      }

      changeSetType match {
        case ChangeSetType.CREATE => resources.reporter.info(s"Stack $stackName not found - no need to update policy")
        case _ => {
          resources.reporter.info(s"Setting update policy for stack $stackName to ${stackPolicy.name}")
          cfnClient.setStackPolicy(
            SetStackPolicyRequest.builder
              .stackName(stackName)
              .stackPolicyBody(stackPolicy.body)
              .build()
          )
        }
      }
    }
  }

  override def description: String = s"Set stack update policy with update strategy $updateStrategy."
}
