package magenta.deployment_type

import magenta.artifact.S3Path
import magenta.fixtures._
import magenta.tasks.{S3Upload, UpdateS3Lambda}
import magenta.{
  App,
  DeployReporter,
  DeployTarget,
  DeploymentPackage,
  DeploymentResources,
  FailException,
  KeyRing,
  Region,
  Stack,
  fixtures
}
import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsBoolean, JsString, JsValue, Json}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.{
  GetParameterRequest,
  GetParameterResponse,
  Parameter,
  SsmException
}
import software.amazon.awssdk.services.sts.StsClient

import java.util.UUID
import scala.concurrent.ExecutionContext.global

class LambdaTest extends AnyFlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  implicit val reporter: DeployReporter =
    DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: S3Client = mock[S3Client]
  implicit val stsClient: StsClient = mock[StsClient]
  val region = Region("eu-west-1")
  val deploymentTypes: Seq[Lambda.type] = Seq(Lambda)

  behavior of "Lambda deployment action uploadLambda"

  val data: Map[String, JsValue] = Map(
    "bucket" -> JsString("lambda-bucket"),
    "fileName" -> JsString("test-file.zip"),
    "prefixStack" -> JsBoolean(false),
    "functionNames" -> Json.arr("MyFunction-")
  )

  val app = App("lambda")
  val pkg = DeploymentPackage(
    "lambda",
    app,
    data,
    "aws-lambda",
    S3Path("artifact-bucket", "test/123/lambda"),
    deploymentTypes
  )
  val defaultRegion = Region("eu-west-1")

  it should "produce an S3 upload task" in {
    val resources = DeploymentResources(
      reporter,
      lookupEmpty,
      artifactClient,
      stsClient,
      global
    )
    val tasks = Lambda
      .actionsMap("uploadLambda")
      .taskGenerator(
        pkg,
        resources,
        DeployTarget(parameters(PROD), Stack("test"), region)
      )
    tasks should be(
      List(
        S3Upload(
          Region("eu-west-1"),
          bucket = "lambda-bucket",
          paths = Seq(
            S3Path(
              "artifact-bucket",
              "test/123/lambda/test-file.zip"
            ) -> s"test/PROD/lambda/test-file.zip"
          ),
          lambdaArtifact = true
        )
      )
    )
  }

  it should "produce a lambda update task" in {
    val tasks = Lambda
      .actionsMap("updateLambda")
      .taskGenerator(
        pkg,
        DeploymentResources(
          reporter,
          lookupEmpty,
          artifactClient,
          stsClient,
          global
        ),
        DeployTarget(parameters(PROD), Stack("test"), region)
      )
    tasks should be(
      List(
        UpdateS3Lambda(
          function = LambdaFunctionName("MyFunction-PROD"),
          s3Bucket = "lambda-bucket",
          s3Key = "test/PROD/lambda/test-file.zip",
          region = defaultRegion
        )
      )
    )
  }

  it should "prefix stack name to function name" in {
    val dataWithoutStackOverride: Map[String, JsValue] = Map(
      "bucket" -> JsString("lambda-bucket"),
      "fileName" -> JsString("test-file.zip"),
      "functionNames" -> Json.arr("MyFunction-")
    )
    val app = App("lambda")
    val pkg = DeploymentPackage(
      "lambda",
      app,
      dataWithoutStackOverride,
      "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"),
      deploymentTypes
    )

    val tasks = Lambda
      .actionsMap("updateLambda")
      .taskGenerator(
        pkg,
        DeploymentResources(
          reporter,
          lookupEmpty,
          artifactClient,
          stsClient,
          global
        ),
        DeployTarget(parameters(PROD), Stack("some-stack"), region)
      )
    tasks should be(
      List(
        UpdateS3Lambda(
          function = LambdaFunctionName("some-stackMyFunction-PROD"),
          s3Bucket = "lambda-bucket",
          s3Key = "some-stack/PROD/lambda/test-file.zip",
          region = defaultRegion
        )
      )
    )
  }

  it should "use tags instead of function names" in {
    val dataWithLookupByTags: Map[String, JsValue] = Map(
      "bucket" -> JsString("lambda-bucket"),
      "fileName" -> JsString("test-file.zip"),
      "lookupByTags" -> JsBoolean(true)
    )
    val app = App("lambda")
    val pkg = DeploymentPackage(
      "lambda",
      app,
      dataWithLookupByTags,
      "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"),
      deploymentTypes
    )

    val tasks = Lambda
      .actionsMap("updateLambda")
      .taskGenerator(
        pkg,
        DeploymentResources(
          reporter,
          lookupEmpty,
          artifactClient,
          stsClient,
          global
        ),
        DeployTarget(parameters(PROD), Stack("some-stack"), region)
      )
    tasks should be(
      List(
        UpdateS3Lambda(
          function = LambdaFunctionTags(
            Map("Stack" -> "some-stack", "Stage" -> "PROD", "App" -> "lambda")
          ),
          s3Bucket = "lambda-bucket",
          s3Key = "some-stack/PROD/lambda/test-file.zip",
          region = defaultRegion
        )
      )
    )
  }

  it should "refuse to work if a bucket name is provided and bucketSsmLookup is true" in {
    val lambdaBucketName = "lambda-bucket"
    val dataWithoutStackOverride: Map[String, JsValue] = Map(
      "bucket" -> JsString(lambdaBucketName),
      "bucketSsmLookup" -> JsBoolean(true),
      "functionNames" -> Json.arr("MyFunction-")
    )
    val app = App("lambda")
    val pkg = DeploymentPackage(
      "lambda",
      app,
      dataWithoutStackOverride,
      "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"),
      deploymentTypes
    )

    val e = the[FailException] thrownBy {
      Lambda
        .actionsMap("updateLambda")
        .taskGenerator(
          pkg,
          DeploymentResources(
            reporter,
            lookupEmpty,
            artifactClient,
            stsClient,
            global
          ),
          DeployTarget(parameters(PROD), Stack("some-stack"), region)
        )
    }

    e.message shouldBe s"Bucket name provided & bucketSsmLookup=true, please choose one or omit both to default to SSM lookup."
  }

  it should "build the S3 location, respecting stack, env, app and file names" in {
    object LambdaTest extends Lambda {}

    val app = App("lambda")
    val deployEnv = PROD
    val stack = Stack("some-stack")
    val deployTarget = DeployTarget(parameters(deployEnv), stack, region)
    val fileName = "test-file.zip"

    def buildDeploymentPackageWithData(data: Map[String, JsValue]) =
      DeploymentPackage(
        "lambda",
        app,
        data,
        "aws-lambda",
        S3Path("artifact-bucket", "test/123/lambda"),
        deploymentTypes
      )

    val defaultData: Map[String, JsValue] = Map()
    val noPrefixData: Map[String, JsValue] = Map(
      "prefixStackToKey" -> Json.parse("false"),
      "prefixStageToKey" -> Json.parse("false"),
      "prefixAppToKey" -> Json.parse("false")
    )
    val skipStackPrefixData: Map[String, JsValue] = Map(
      "prefixStageToKey" -> Json.parse("false"),
      "prefixAppToKey" -> Json.parse("false")
    )
    val skipStagePrefixData: Map[String, JsValue] = Map(
      "prefixStackToKey" -> Json.parse("false"),
      "prefixAppToKey" -> Json.parse("false")
    )
    val skipAppPrefixData: Map[String, JsValue] = Map(
      "prefixStackToKey" -> Json.parse("false"),
      "prefixStageToKey" -> Json.parse("false")
    )
    val skipStageAndStackPrefixData: Map[String, JsValue] = Map(
      "prefixAppToKey" -> Json.parse("false")
    )

    val testCases = Map(
      defaultData -> s"${stack.name}/${deployEnv.name}/${app.name}/${fileName}",
      skipStackPrefixData -> s"${stack.name}/${fileName}",
      skipStagePrefixData -> s"${deployEnv.name}/${fileName}",
      skipAppPrefixData -> s"${app.name}/${fileName}",
      skipStageAndStackPrefixData -> s"${stack.name}/${deployEnv.name}/${fileName}",
      noPrefixData -> fileName
    )

    testCases.foreach { case (data, expectedS3Key) =>
      val pkg = buildDeploymentPackageWithData(data)
      val s3Key = LambdaTest.makeS3Key(
        target = deployTarget,
        pkg = pkg,
        fileName = fileName,
        reporter = reporter
      )

      s3Key shouldBe expectedS3Key
    }
  }

  it should "refuse to work if bucket name is not provided and no bucket is specified in SSM" in {
    val dataWithoutStackOverride: Map[String, JsValue] = Map(
      "functionNames" -> Json.arr("MyFunction-")
    )
    val app = App("lambda")
    val pkg = DeploymentPackage(
      "lambda",
      app,
      dataWithoutStackOverride,
      "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"),
      deploymentTypes
    )

    val ssmClient = mock[SsmClient]

    when(
      ssmClient.getParameter(ArgumentMatchers.any(classOf[GetParameterRequest]))
    ).thenThrow(
      SsmException.builder.message("Boom!").build()
    )

    object LambdaTest extends Lambda {
      override def withSsm[T](
          keyRing: KeyRing,
          region: Region,
          resources: DeploymentResources
      ): (SsmClient => T) => T = _(ssmClient)
    }

    val e = the[FailException] thrownBy {
      LambdaTest
        .actionsMap("updateLambda")
        .taskGenerator(
          pkg,
          DeploymentResources(
            reporter,
            lookupEmpty,
            artifactClient,
            stsClient,
            global
          ),
          DeployTarget(parameters(PROD), Stack("some-stack"), region)
        )
    }

    val ssmKey = BucketParametersDefaults.defaultSsmKeyParamDefault
    e.message shouldBe s"Explicit bucket name has not been provided and failed to read bucket from SSM parameter: $ssmKey"
  }

  it should "default to lookup bucket from SSM when bucket name is not provided and bucketSsmLookup is false" in {
    val dataWithoutStackOverride: Map[String, JsValue] = Map(
      "functionNames" -> Json.arr("MyFunction-")
    )
    val app = App("lambda")
    val pkg = DeploymentPackage(
      "lambda",
      app,
      dataWithoutStackOverride,
      "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"),
      deploymentTypes
    )

    val ssmClient = mock[SsmClient]

    when(
      ssmClient.getParameter(ArgumentMatchers.any(classOf[GetParameterRequest]))
    ).thenReturn(
      GetParameterResponse.builder
        .parameter(Parameter.builder.value("bobbins").build)
        .build
    )
    object LambdaTest extends Lambda {
      override def withSsm[T](
          keyRing: KeyRing,
          region: Region,
          resources: DeploymentResources
      ): (SsmClient => T) => T = _(ssmClient)
    }

    val tasks = LambdaTest
      .actionsMap("updateLambda")
      .taskGenerator(
        pkg,
        DeploymentResources(
          reporter,
          lookupEmpty,
          artifactClient,
          stsClient,
          global
        ),
        DeployTarget(parameters(PROD), Stack("some-stack"), region)
      )

    tasks shouldBe List(
      UpdateS3Lambda(
        function = LambdaFunctionName("some-stackMyFunction-PROD"),
        s3Bucket = s"bobbins",
        s3Key = "some-stack/PROD/lambda/lambda.zip",
        region = defaultRegion
      )
    )

  }

  it should "lookup bucket from SSM when bucketSsmLookup is true" in {
    val dataWithoutStackOverride: Map[String, JsValue] = Map(
      "bucketSsmLookup" -> JsBoolean(true),
      "functionNames" -> Json.arr("MyFunction-")
    )
    val app = App("lambda")
    val pkg = DeploymentPackage(
      "lambda",
      app,
      dataWithoutStackOverride,
      "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"),
      deploymentTypes
    )

    val ssmClient = mock[SsmClient]

    when(
      ssmClient.getParameter(ArgumentMatchers.any(classOf[GetParameterRequest]))
    ).thenReturn(
      GetParameterResponse.builder
        .parameter(Parameter.builder.value("bobbins").build)
        .build
    )
    object LambdaTest extends Lambda {
      override def withSsm[T](
          keyRing: KeyRing,
          region: Region,
          resources: DeploymentResources
      ): (SsmClient => T) => T = _(ssmClient)
    }

    val tasks = LambdaTest
      .actionsMap("updateLambda")
      .taskGenerator(
        pkg,
        DeploymentResources(
          reporter,
          lookupEmpty,
          artifactClient,
          stsClient,
          global
        ),
        DeployTarget(parameters(PROD), Stack("some-stack"), region)
      )

    tasks shouldBe List(
      UpdateS3Lambda(
        function = LambdaFunctionName("some-stackMyFunction-PROD"),
        s3Bucket = s"bobbins",
        s3Key = "some-stack/PROD/lambda/lambda.zip",
        region = defaultRegion
      )
    )

  }

  it should "omit prefix when prefixStackToKeyParam is set to false" in {
    val dataWithLookupByTags: Map[String, JsValue] = Map(
      "bucket" -> JsString("lambda-bucket"),
      "fileName" -> JsString("test-file.zip"),
      "lookupByTags" -> JsBoolean(true),
      "prefixStackToKey" -> JsBoolean(false)
    )
    val app = App("lambda")
    val pkg = DeploymentPackage(
      "lambda",
      app,
      dataWithLookupByTags,
      "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"),
      deploymentTypes
    )

    val tasks = Lambda
      .actionsMap("updateLambda")
      .taskGenerator(
        pkg,
        DeploymentResources(
          reporter,
          lookupEmpty,
          artifactClient,
          stsClient,
          global
        ),
        DeployTarget(parameters(PROD), Stack("some-stack"), region)
      )
    tasks should be(
      List(
        UpdateS3Lambda(
          function = LambdaFunctionTags(
            Map("Stack" -> "some-stack", "Stage" -> "PROD", "App" -> "lambda")
          ),
          s3Bucket = "lambda-bucket",
          s3Key = "PROD/lambda/test-file.zip",
          region = defaultRegion
        )
      )
    )
  }

}
