package magenta.input.resolver

import java.util.UUID

import cats.data.{NonEmptyList => NEL}
import com.amazonaws.services.s3.AmazonS3Client
import magenta.artifact.S3YamlArtifact
import magenta.deployment_type.{Action, AutoScaling}
import magenta.fixtures._
import magenta.input.Deployment
import magenta.{Build, DeployParameters, DeployReporter, Deployer, DeploymentResources, Stack, Region, Stage, fixtures}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.JsString

class TaskResolverTest extends FlatSpec with Matchers with MockitoSugar with ValidatedValues {
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient = mock[AmazonS3Client]

  val deploymentTypes = List(StubDeploymentType(
    actionsMap = Map(
      "uploadArtifact" -> Action("uploadArtifact"){
        (pkg, resources, target) => List(StubTask("upload", target.region, stack = Some(target.stack)))
      }(StubActionRegister),
      "deploy" -> Action("deploy"){
        (pkg, resources, target) => List(StubTask("deploy", target.region, stack = Some(target.stack)))
      }(StubActionRegister)
    ),
    List("uploadArtifact", "deploy"))
  )

  "resolve" should "produce a deployment task" in {
    val deploymentTask = TaskResolver.resolve(
      deployment = Deployment("test", "stub-package-type", NEL.of("stack"), NEL.of("region"),
        NEL.of("uploadArtifact", "deploy"), "app", "directory", Nil, Map("bucket" -> JsString("bucketName"))),
      deploymentResources = DeploymentResources(reporter, stubLookup(), artifactClient),
      parameters = DeployParameters(Deployer("Test user"), Build("test-project", "1"), Stage("PROD")),
      deploymentTypes = deploymentTypes,
      artifact = S3YamlArtifact("artifact-bucket", "/path/to/test-project/1")
    )

    deploymentTask.valid.name shouldBe "test [uploadArtifact, deploy] => region/stack"
    deploymentTask.valid.tasks shouldBe List(
      StubTask("upload", Region("region"), stack = Some(Stack("stack"))),
      StubTask("deploy", Region("region"), stack = Some(Stack("stack")))
    )
  }

  "resolve" should "produce a deployment task with multiple regions" in {
    val deploymentTask = TaskResolver.resolve(
      deployment = Deployment("test", "stub-package-type", NEL.of("stack"), NEL.of("region-one", "region-two"),
        NEL.of("uploadArtifact", "deploy"), "app", "directory", Nil, Map("bucket" -> JsString("bucketName"))),
      deploymentResources = DeploymentResources(reporter, stubLookup(), artifactClient),
      parameters = DeployParameters(Deployer("Test user"), Build("test-project", "1"), Stage("PROD")),
      deploymentTypes = deploymentTypes,
      artifact = S3YamlArtifact("artifact-bucket", "/path/to/test-project/1")
    )

    deploymentTask.valid.name shouldBe "test [uploadArtifact, deploy] => {region-one,region-two}/stack"
    deploymentTask.valid.tasks shouldBe List(
      StubTask("upload", Region("region-one"), stack = Some(Stack("stack"))),
      StubTask("deploy", Region("region-one"), stack = Some(Stack("stack"))),
      StubTask("upload", Region("region-two"), stack = Some(Stack("stack"))),
      StubTask("deploy", Region("region-two"), stack = Some(Stack("stack")))
    )
  }

  "resolve" should "produce an error when the deployment type isn't found" in {
    val deploymentTask = TaskResolver.resolve(
      deployment = Deployment("test", "autoscaling", NEL.of("stack"), NEL.of("region"), NEL.of("uploadArtifact", "deploy"), "app", "directory", Nil, Map("bucket" -> JsString("bucketName"))),
      deploymentResources = DeploymentResources(reporter, stubLookup(), artifactClient),
      parameters = DeployParameters(Deployer("Test user"), Build("test-project", "1"), Stage("PROD")),
      deploymentTypes = Nil,
      artifact = S3YamlArtifact("artifact-bucket", "/path/to/test-project/1")
    )

    deploymentTask.invalid.errors.head.context shouldBe "test"
    deploymentTask.invalid.errors.head.message shouldBe "Deployment type autoscaling not found"
  }
}
