package docs

import magenta.deployment_type._
import org.scalatest.{FlatSpec, Matchers}

class DeployTypeDocsTest extends FlatSpec with Matchers {
  "generateParamDocs" should "generate documentation for our fake deployment type" in {
    val testDeploymentType = new DeploymentType {
      def actions = ???
      def defaultActions = ???

      def name = "testDeploymentType"
      def documentation = "This is some documentation"

      val param1 = Param[String]("param1", "first parameter which has no default")
      val simianType = Param[String]("simianType", "simianType param which has a default").default("monkey")
      val foodType = Param[String]("foodType", "another param with a contextual default").
        defaultFromContext((pkg, target) => Right(s"banana${target.stack.nameOption.getOrElse("")}"))
      val foodCount = Param[Int]("foodCount", "param with different default for legacy and new").
        defaultFromContext((pkg, target) =>
          Right(if (pkg.legacyConfig) 1 else 100)
        )
    }
    val result = DeployTypeDocs.generateParamDocs(Seq(testDeploymentType))
    result.size shouldBe 1
    val (dt, paramDocs) = result.head
    dt shouldBe testDeploymentType
    paramDocs.size shouldBe 4
    paramDocs should contain (("param1", "first parameter which has no default", None, None))
    paramDocs should contain (("simianType", "simianType param which has a default", Some("monkey"), Some("monkey")))
    paramDocs should contain (("foodType", "another param with a contextual default", Some("banana<stack>"), Some("banana<stack>")))
    paramDocs should contain (("foodCount", "param with different default for legacy and new", Some("1"), Some("100")))
  }

  it should "successfully generate documentation for the standard set of deployment types" in {
    // we can't easily check correctness, but we can check exceptions are not thrown
    val availableDeploymentTypes = Seq(
      ElasticSearch, S3, AutoScaling, Fastly, CloudFormation, Lambda, AmiCloudFormationParameter, SelfDeploy
    )
    val result = DeployTypeDocs.generateParamDocs(availableDeploymentTypes)
    result.size shouldBe availableDeploymentTypes.size
  }
}
