package docs

import magenta.deployment_type._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DeployTypeDocsTest extends AnyFlatSpec with Matchers {
  "generateDocs" should "generate documentation for our fake deployment type" in {
    val testDeploymentType = new DeploymentType {
      val action =
        Action("myAction", "some docs for my action")((_, _, _) => Nil)
      val action2 = Action(
        "myNonDefaultAction",
        "some docs for my action that doesn't run by default"
      )((_, _, _) => Nil)
      override def defaultActions = List(action)

      override def name = "testDeploymentType"
      override def documentation = "This is some documentation"

      val param1 =
        Param[String]("param1", "first parameter which has no default")
      val simianType =
        Param[String]("simianType", "simianType param which has a default")
          .default("monkey")
      val foodType =
        Param[String]("foodType", "another param with a contextual default")
          .defaultFromContext((pkg, target) =>
            Right(s"banana${target.stack.name}")
          )
      val foodCount = Param[Int](
        "foodCount",
        "param with different default for legacy and new"
      ).defaultFromContext((pkg, target) => Right(100))
    }
    val result = DeployTypeDocs.generateDocs(Seq(testDeploymentType))
    result.size shouldBe 1
    val (dt, DeployTypeDocs(docs, actionDocs, paramDocs)) = result.head
    dt shouldBe testDeploymentType

    docs shouldBe testDeploymentType.documentation

    actionDocs.size shouldBe 2
    actionDocs should contain(
      ActionDoc("myAction", "some docs for my action", true)
    )
    actionDocs should contain(
      ActionDoc(
        "myNonDefaultAction",
        "some docs for my action that doesn't run by default",
        false
      )
    )

    paramDocs.size shouldBe 4
    paramDocs should contain(
      ParamDoc("param1", "first parameter which has no default", None)
    )
    paramDocs should contain(
      ParamDoc(
        "simianType",
        "simianType param which has a default",
        Some("monkey")
      )
    )
    paramDocs should contain(
      ParamDoc(
        "foodType",
        "another param with a contextual default",
        Some("banana<stack>")
      )
    )
    paramDocs should contain(
      ParamDoc(
        "foodCount",
        "param with different default for legacy and new",
        Some("100")
      )
    )
  }

  it should "successfully generate documentation for the standard set of deployment types" in {
    // we can't easily check correctness, but we can check exceptions are not thrown
    val availableDeploymentTypes = Seq(
      S3,
      AutoScaling,
      Fastly,
      new CloudFormation(EmptyBuildTags),
      Lambda,
      AmiCloudFormationParameter,
      SelfDeploy
    )
    val result = DeployTypeDocs.generateDocs(availableDeploymentTypes)
    result.size shouldBe availableDeploymentTypes.size
  }
}
