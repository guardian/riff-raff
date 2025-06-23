package magenta.tasks

import magenta.tasks.stackSetPolicy.{AllowAllPolicy, DenyReplaceDeletePolicy, StackPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.services.elasticloadbalancingv2.model.{
  TagDescription => _
}

class SetStackPolicyTaskTest extends AnyFlatSpec with Matchers {

  "toPolicyDoc" should "return an allow doc when policy is AllowAllPolicy" in {
    val got = StackPolicy.toPolicyDoc(AllowAllPolicy, () => Set.empty)
    val want =
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

    got shouldBe want
  }

  it should "return a deny doc when policy is DenyReplaceDeletePolicy, including supported resource types" in {
    val got = StackPolicy.toPolicyDoc(
      DenyReplaceDeletePolicy,
      () =>
        Set(
          // sensitive AWS types
          "AWS::DocDB::DBCluster",
          "AWS::Kinesis::Stream",

          // non-sensitive AWS type, should not appear in the policy
          "AWS::IAM::Role",

          // sensitive private type
          "Guardian::DNS::RecordSet"
        )
    )

    val want =
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
         |            "AWS::DocDB::DBCluster","AWS::Kinesis::Stream","Guardian::DNS::RecordSet"
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

    got shouldBe want
  }

  it should "return a deny doc when policy is DenyReplaceDeletePolicy, excluding unsupported private types" in {
    val got = StackPolicy.toPolicyDoc(
      DenyReplaceDeletePolicy,
      () =>
        Set(
          "AWS::Kinesis::Stream", // sensitive AWS type
          "AWS::IAM::Role" // non-sensitive AWS type, should not appear in the policy
        )
    )

    val want =
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
         |            "AWS::Kinesis::Stream"
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

    got shouldBe want
  }
}
