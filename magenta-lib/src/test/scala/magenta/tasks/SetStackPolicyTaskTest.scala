package magenta.tasks

import magenta.tasks.StackPolicy.allSensitiveResourceTypes
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.services.elasticloadbalancingv2.model.{TagDescription => _}

class SetStackPolicyTaskTest extends AnyFlatSpec with Matchers {

  "toPolicyDoc" should "return an allow doc when policy is AllowAllPolicy" in {
    val got = StackPolicy.toPolicyDoc(AllowAllPolicy, StackPolicy.allSensitiveResourceTypes, () => Set.empty)
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

  it should "return a deny doc when policy is DenyReplaceDeletePolicy, including supported private types" in {
    val got = StackPolicy.toPolicyDoc(
      DenyReplaceDeletePolicy,
      StackPolicy.allSensitiveResourceTypes,
      () => Set("Guardian::DNS::RecordSet")
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
         |            ${allSensitiveResourceTypes.mkString("\"","\",\n\"", "\"")}
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
      StackPolicy.allSensitiveResourceTypes,
      () => Set.empty
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
         |            ${allSensitiveResourceTypes.filterNot(_ == "Guardian::DNS::RecordSet").mkString("\"","\",\n\"", "\"")}
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
