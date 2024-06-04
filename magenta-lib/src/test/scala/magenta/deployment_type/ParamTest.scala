package magenta.deployment_type

import java.util.UUID
import magenta.artifact.S3Path
import magenta._
import magenta.fixtures._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.mockito.MockitoSugar
import play.api.libs.json.{JsNumber, JsString, JsValue}

import java.time.Duration.ofSeconds
import scala.collection.mutable

class ParamRegistrationTest extends AnyFlatSpec with Matchers {
  class TestRegister extends ParamRegister {
    val paramsList = mutable.Map.empty[String, Param[_]]
    def add(param: Param[_]) = paramsList += param.name -> param
  }

  "Param registration" should "occur when a param is created" in {
    implicit val register: TestRegister = new TestRegister

    val param = Param[String]("test")

    register.paramsList.size shouldBe 1
    register.paramsList shouldBe Map("test" -> param)
  }
}

class ParamTest extends AnyFlatSpec with Matchers with MockitoSugar {
  val target = DeployTarget(
    fixtures.parameters(),
    Stack("testStack"),
    Region("testRegion")
  )
  val reporter =
    DeployReporter.rootReporterFor(UUID.randomUUID(), target.parameters)
  val deploymentTypes = Seq(
    stubDeploymentType(
      name = "testDeploymentType",
      actionNames = Seq("testAction")
    )
  )
  def stubDeploymentPackage(pkgSpecificData: Map[String, JsValue]) =
    DeploymentPackage(
      "testPackage",
      app1,
      pkgSpecificData,
      "testDeploymentType",
      S3Path("test", "test"),
      deploymentTypes
    )
  val Key = "demoParamName"
  private val pkgWithEmptyConfig: DeploymentPackage =
    stubDeploymentPackage(Map.empty)

  implicit val stubRegister: ParamRegister =
    (_: Param[_]) =>
      () // We don't care about param registration for these tests

  "Param" should "treat values as seconds when we've been explicit about it" in {
    val key = Param
      .waitingSecondsFor(Key, "something important to happen")
      .default(ofSeconds(7))
    key.documentation shouldBe "Number of seconds to wait for something important to happen"
    key.get(stubDeploymentPackage(Map(Key -> JsNumber(15)))) shouldBe Some(
      ofSeconds(15)
    )
  }

  it should "extract a value from a package using get" in {
    val param = Param[String](Key).default("valueDefault")
    val paramValue =
      param.get(stubDeploymentPackage(Map(Key -> JsString("myValue"))))
    paramValue shouldBe Some("myValue")
  }

  it should "extract None using get when the value isn't in a package" in {
    Param[String](Key).default("d").get(pkgWithEmptyConfig) shouldBe None
  }

  it should "extract a value using apply" in {
    val pkg = stubDeploymentPackage(Map(Key -> JsString("myValue")))
    val param = Param[String](Key).default("valueDefault")
    param.apply(pkg, target, reporter) shouldBe "myValue"
  }

  it should "throw an exception if a value is not specified and has no default" in {
    val param = Param[String](Key)
    val thrown = the[NoSuchElementException] thrownBy {
      param.apply(pkgWithEmptyConfig, target, reporter)
    }
    thrown.getMessage shouldBe s"Package testPackage [testDeploymentType] requires parameter $Key of type String"
  }

  it should "return the param default from apply when no value is specified" in {
    val param = Param[String](Key).default("valueDefault")
    param.apply(pkgWithEmptyConfig, target, reporter) shouldBe "valueDefault"
  }

  it should "return the param context default from apply when no value is specified" in {
    val param = Param[String](Key).defaultFromContext((_, target) =>
      Right(s"${target.region.name}")
    )
    val paramValue = param.apply(pkgWithEmptyConfig, target, reporter)
    paramValue shouldBe target.region.name
  }

  it should "throw an exception if defaultFromContext returns a Left value" in {
    val key = Param[String](Key).defaultFromContext((_, _) =>
      Left("something was wrong")
    )
    val thrown = the[NoSuchElementException] thrownBy {
      key.apply(pkgWithEmptyConfig, target, reporter)
    }
    thrown.getMessage shouldBe s"Error whilst generating default for parameter $Key in package testPackage [testDeploymentType]: something was wrong"
  }

  it should "log if the value you've specified is the same as the default" in {
    val mockReporter = mock[DeployReporter]
    val pkg = stubDeploymentPackage(Map(Key -> JsString("sameValue")))
    val paramValue =
      Param[String](Key).default("sameValue").apply(pkg, target, mockReporter)
    paramValue shouldBe "sameValue"
    verify(mockReporter).info(
      s"Parameter $Key is unnecessarily explicitly set to the default value of sameValue"
    )
  }

  it should "log if the value you've specified is the same as the default from context" in {
    val mockReporter = mock[DeployReporter]
    val pkg = stubDeploymentPackage(Map(Key -> JsString("sameValue")))
    val param =
      Param[String](Key).defaultFromContext((_, _) => Right("sameValue"))
    param.apply(pkg, target, mockReporter) shouldBe "sameValue"
    verify(mockReporter).info(
      s"Parameter $Key is unnecessarily explicitly set to the default value of sameValue"
    )
  }
}
