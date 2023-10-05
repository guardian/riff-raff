package magenta.deployment_type
import magenta.artifact.S3Path
import magenta.tasks.S3.BucketBySsmKey
import magenta.{
  App,
  DeployReporter,
  DeployTarget,
  DeploymentPackage,
  FailException,
  Region,
  Stack,
  Stage,
  fixtures
}
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsBoolean, JsObject, JsString, JsValue}

import java.util.UUID

object Test extends DeploymentType with BucketParameters {
  def defaultActions = Nil
  def documentation = "n/a"
  def name = "n/a"
}

class BucketParametersTest extends AnyFlatSpec with Matchers with MockitoSugar {
  val target = DeployTarget(
    fixtures.parameters(),
    Stack("testStack"),
    Region("testRegion")
  )
  val reporter =
    DeployReporter.rootReporterFor(UUID.randomUUID(), target.parameters)

  "Bucket parameters" should "default to SSM lookup even if bucketSsmLookup=false" in {
    val want = "/example-path"
    val pkg = BucketParametersTest.pkg(
      Map(
        "bucketSsmLookup" -> JsBoolean(false),
        "bucketSsmKey" -> JsString(want)
      )
    )

    val got = Test.getTargetBucketFromConfig(pkg, target, reporter)
    got shouldBe BucketBySsmKey(want)
  }

  it should "lookup SSM parameter by stage" in {
    val targetForCode = DeployTarget(
      fixtures.parameters(stage = Stage("CODE")),
      Stack("testStack"),
      Region("testRegion")
    )

    val want = "/code-path"
    val pkg = BucketParametersTest.pkg(
      Map(
        "bucketSsmKeyStageParam" -> JsObject(
          List("CODE" -> JsString(want), "PROD" -> JsString("/prod-path"))
        )
      )
    )

    val got = Test.getTargetBucketFromConfig(pkg, targetForCode, reporter)
    got shouldBe BucketBySsmKey(want)
  }

  it should "warn if setting explicit bucket name" in {
    val mockReporter = mock[DeployReporter]
    val pkg = BucketParametersTest.pkg(
      Map("bucket" -> JsString("some-bucket-name"))
    )

    val _ = Test.getTargetBucketFromConfig(pkg, target, mockReporter)
    verify(mockReporter).warning(
      "riff-raff.yaml exposes the bucket name. Prefer to use bucketSsmLookup=true and store bucket name in parameter store, removing private information from version control."
    )
  }

  it should "fail if explicit bucket name set and bucketSsmLookup=true" in {
    a[FailException] should be thrownBy {
      val pkg = BucketParametersTest.pkg(
        Map(
          "bucket" -> JsString("some-bucket-name"),
          "bucketSsmLookup" -> JsBoolean(true)
        )
      )

      val _ = Test.getTargetBucketFromConfig(pkg, target, reporter)
    }
  }
}

object BucketParametersTest {
  def pkg(params: Map[String, JsValue]): DeploymentPackage = {
    DeploymentPackage(
      "n/a",
      App("n/a"),
      params,
      "n/a",
      S3Path("test", "test"),
      Seq(Test)
    )
  }
}
