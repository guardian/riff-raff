package magenta.deployment_type

import java.util.UUID

import magenta.artifact.S3Path
import magenta._
import magenta.fixtures._
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.JsString

import scala.collection.mutable

class TestRegister extends ParamRegister {
  val paramsList = mutable.Map.empty[String, Param[_]]
  def add(param: Param[_]) = paramsList += param.name -> param
}

class ParamTest extends FlatSpec with Matchers with MockitoSugar {
  val target = DeployTarget(fixtures.parameters(), NamedStack("testStack"), Region("testRegion"))
  val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), target.parameters)
  val deploymentTypes = Seq(stubDeploymentType(name = "testDeploymentType", actionNames = Seq("testAction")))

  "Param" should "register itself with a register" in {
    implicit val register = new TestRegister

    val param = Param[String]("test")

    register.paramsList.size shouldBe 1
    register.paramsList should contain("test" -> param)
  }

  it should "extract a value from a package using get" in {
    implicit val register = new TestRegister
    val pkg = DeploymentPackage("testPackage",
                                Nil,
                                Map("key" -> JsString("myValue")),
                                "testDeploymentType",
                                S3Path("test", "test"),
                                legacyConfig = true,
                                deploymentTypes)
    val key = Param[String]("key").default("valueDefault")
    val paramValue = key.get(pkg)
    paramValue shouldBe Some("myValue")
  }

  it should "extract None using get when the value isn't in a package" in {
    implicit val register = new TestRegister
    val pkg = DeploymentPackage("testPackage",
                                Nil,
                                Map.empty,
                                "testDeploymentType",
                                S3Path("test", "test"),
                                legacyConfig = true,
                                deploymentTypes)
    val key = Param[String]("key").default("valueDefault")
    val paramValue = key.get(pkg)
    paramValue shouldBe None
  }

  it should "extract a value using apply" in {
    implicit val register = new TestRegister
    val pkg = DeploymentPackage("testPackage",
                                Nil,
                                Map("key" -> JsString("myValue")),
                                "testDeploymentType",
                                S3Path("test", "test"),
                                legacyConfig = true,
                                deploymentTypes)
    val key = Param[String]("key").default("valueDefault")
    val paramValue = key.apply(pkg, target, reporter)
    paramValue shouldBe "myValue"
  }

  it should "throw an exception if a value is not specified and has no default" in {
    implicit val register = new TestRegister
    val pkg = DeploymentPackage("testPackage",
                                Nil,
                                Map.empty,
                                "testDeploymentType",
                                S3Path("test", "test"),
                                legacyConfig = true,
                                deploymentTypes)
    val key = Param[String]("key")
    val thrown = the[NoSuchElementException] thrownBy {
      key.apply(pkg, target, reporter)
    }
    thrown.getMessage shouldBe "Package testPackage [testDeploymentType] requires parameter key of type String"
  }

  it should "return the param default from apply when no value is specified" in {
    implicit val register = new TestRegister
    val pkg = DeploymentPackage("testPackage",
                                Nil,
                                Map.empty,
                                "testDeploymentType",
                                S3Path("test", "test"),
                                legacyConfig = true,
                                deploymentTypes)
    val key = Param[String]("key").default("valueDefault")
    val paramValue = key.apply(pkg, target, reporter)
    paramValue shouldBe "valueDefault"
  }

  it should "return the param context default from apply when no value is specified" in {
    implicit val register = new TestRegister
    val pkg = DeploymentPackage("testPackage",
                                Nil,
                                Map.empty,
                                "testDeploymentType",
                                S3Path("test", "test"),
                                legacyConfig = true,
                                deploymentTypes)
    val key =
      Param[String]("key").defaultFromContext((pkg, target) => Right(s"${target.region.name} and ${pkg.legacyConfig}"))
    val paramValue = key.apply(pkg, target, reporter)
    paramValue shouldBe "testRegion and true"
  }

  it should "throw an exception if defaultFromContext returns a Left value" in {
    implicit val register = new TestRegister
    val pkg = DeploymentPackage("testPackage",
                                Nil,
                                Map.empty,
                                "testDeploymentType",
                                S3Path("test", "test"),
                                legacyConfig = true,
                                deploymentTypes)
    val key = Param[String]("key").defaultFromContext((pkg, target) => Left("something was wrong"))
    val thrown = the[NoSuchElementException] thrownBy {
      key.apply(pkg, target, reporter)
    }
    thrown.getMessage shouldBe "Error whilst generating default for parameter key in package testPackage [testDeploymentType]: something was wrong"
  }

  it should "log a warning if the value you've specified is the same as the default" in {
    val mockReporter = mock[DeployReporter]
    implicit val register = new TestRegister
    val pkg = DeploymentPackage("testPackage",
                                Nil,
                                Map("key" -> JsString("sameValue")),
                                "testDeploymentType",
                                S3Path("test", "test"),
                                legacyConfig = false,
                                deploymentTypes)
    val key = Param[String]("key").default("sameValue")
    val paramValue = key.apply(pkg, target, mockReporter)
    paramValue shouldBe "sameValue"
    verify(mockReporter).warning("Parameter key is unnecessarily explicitly set to the default value of sameValue")
  }

  it should "log a warning if the value you've specified is the same as the default from context" in {
    val mockReporter = mock[DeployReporter]
    implicit val register = new TestRegister
    val pkg = DeploymentPackage("testPackage",
                                Nil,
                                Map("key" -> JsString("sameValue")),
                                "testDeploymentType",
                                S3Path("test", "test"),
                                legacyConfig = false,
                                deploymentTypes)
    val key = Param[String]("key").defaultFromContext((_, _) => Right("sameValue"))
    val paramValue = key.apply(pkg, target, mockReporter)
    paramValue shouldBe "sameValue"
    verify(mockReporter).warning("Parameter key is unnecessarily explicitly set to the default value of sameValue")
  }

  it should "not log warning if the value you've specified is the same as the default but it is a legacy config" in {
    val mockReporter = mock[DeployReporter]
    implicit val register = new TestRegister
    val pkg = DeploymentPackage("testPackage",
                                Nil,
                                Map("key" -> JsString("sameValue")),
                                "testDeploymentType",
                                S3Path("test", "test"),
                                legacyConfig = true,
                                deploymentTypes)
    val key = Param[String]("key").default("sameValue")
    val paramValue = key.apply(pkg, target, mockReporter)
    paramValue shouldBe "sameValue"
    verify(mockReporter, never).warning(any())
  }
}
