package magenta.deployment_type

import java.io.File

import magenta._
import magenta.tasks._
import magenta.tasks.UpdateCloudFormationTask._
import org.json4s.JsonAST.JValue
import org.scalatest.{FlatSpec, Matchers}
import fixtures._

import scalax.file.Path

class CloudFormationTest extends FlatSpec with Matchers {
  implicit val fakeKeyRing = KeyRing(SystemUser(None))

  "cloudformation deployment type" should "have an updateStack action" in {
    val data: Map[String, JValue] = Map()
    val app = Seq(App("app"))
    val stack = NamedStack("cfn")
    val cfnStackName = s"cfn-app-PROD"
    val p = DeploymentPackage("app", app, data, "cloudformation", new File("/tmp/packages/webapp"))

    CloudFormation.perAppActions("updateStack")(p)(lookupEmpty, parameters(), stack) should be (List(
      UpdateCloudFormationTask(
        cfnStackName,
        Path(p.srcDir) \ Path.fromString("""cloud-formation/cfn.json"""),
        Map.empty,
        PROD,
        NamedStack("cfn"),
        true
      ),
      CheckUpdateEventsTask(cfnStackName)
    ))
  }

  "UpdateCloudFormationTask" should "substitute stack and stage parameters" in {
    val task = UpdateCloudFormationTask(
      "testStack",
      Path.fromString("""cloud-formation/cfn.json"""),
      Map("param1" -> "value1"),
      PROD,
      NamedStack("cfn"),
      true
    )

    val templateParameters =
      Seq(TemplateParameter("param1", false), TemplateParameter("Stack", false), TemplateParameter("Stage", false))
    val combined = task.combineParameters(templateParameters)

    combined should be(Map(
      "param1" -> SpecifiedValue("value1"),
      "Stack" -> SpecifiedValue("cfn"),
      "Stage" -> SpecifiedValue("PROD")
      ))
  }

  it should "default required parameters to use existing parameters" in {
    val task = UpdateCloudFormationTask(
      "testStack",
      Path.fromString("""cloud-formation/cfn.json"""),
      Map("param1" -> "value1"),
      PROD,
      NamedStack("cfn"),
      true
    )

    val templateParameters =
      Seq(TemplateParameter("param1", true), TemplateParameter("param3", false), TemplateParameter("Stage", false))
    val combined = task.combineParameters(templateParameters)

    combined should be(Map(
      "param1" -> SpecifiedValue("value1"),
      "param3" -> UseExistingValue,
      "Stage" -> SpecifiedValue(PROD.name)
    ))
  }

  "JsonConverter" should "convert YAML to JSON" in {
    val yaml =
      """
        |AWSTemplateFormatVersion: 2010-09-09
        |Description: 'Example CloudFormation template'
        |Parameters:
        |  FirstParam:
        |    Description: An EC2 key pair
        |    Type: AWS::EC2::KeyPair::KeyName
      """.stripMargin

    val expectedJson =
      """
        |{
        |  "AWSTemplateFormatVersion":"2010-09-09",
        |  "Description":"Example CloudFormation template",
        |  "Parameters":{
        |    "FirstParam":{
        |      "Description":"An EC2 key pair",
        |      "Type":"AWS::EC2::KeyPair::KeyName"
        |    }
        |  }
        |}
      """.stripMargin.trim

    val yamlFile = Path.createTempFile(suffix = ".yml")
    try {
      yamlFile.append(yaml)
      JsonConverter.convert(yamlFile) should be(expectedJson)
    } finally {
      yamlFile.delete()
    }
  }

  "JsonConverter" should "assume a file with a non-YAML extension already contains JSON" in {
    val json = """{"some":"json"}"""
    val jsonFile = Path.createTempFile(suffix = ".template")
    try {
      jsonFile.append(json)
      JsonConverter.convert(jsonFile) should be(json)
    } finally {
      jsonFile.delete()
    }

  }
}
