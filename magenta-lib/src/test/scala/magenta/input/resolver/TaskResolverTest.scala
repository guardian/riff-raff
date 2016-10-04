package magenta.input.resolver

import java.util.UUID

import com.amazonaws.services.s3.AmazonS3Client
import magenta.artifact.S3YamlArtifact
import magenta.{Build, DeployParameters, DeployReporter, Deployer, DeploymentResources, NamedStack, Region, Stage, fixtures}
import magenta.input.Deployment
import org.scalatest.{EitherValues, FlatSpec, Matchers}
import play.api.libs.json.JsString
import magenta.fixtures._
import org.scalatest.mock.MockitoSugar

class TaskResolverTest extends FlatSpec with Matchers with MockitoSugar with EitherValues {
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient = mock[AmazonS3Client]

  val deploymentTypes = List(StubDeploymentType(
    actions = {
      case "uploadArtifact" => pkg => (resources, target) => List(StubTask("upload", target.region, stack = Some(target.stack)))
      case "deploy" => pkg => (resources, target) => List(StubTask("deploy", target.region, stack = Some(target.stack)))
    },
    List("uploadArtifact", "deploy"))
  )

  "resolve" should "produce a deployment task" in {
    val deploymentTask = TaskResolver.resolve(
      deployment = Deployment("test", "stub-package-type", List("stack"), List("region"),
        Some(List("uploadArtifact", "deploy")), "app", "directory", Nil, Map("bucket" -> JsString("bucketName"))),
      deploymentResources = DeploymentResources(reporter, stubLookup(), artifactClient),
      parameters = DeployParameters(Deployer("Test user"), Build("test-project", "1"), Stage("PROD")),
      deploymentTypes = deploymentTypes,
      artifact = S3YamlArtifact("artifact-bucket", "/path/to/test-project/1")
    )

    deploymentTask.right.value.name shouldBe "test [uploadArtifact, deploy] => region/stack"
    deploymentTask.right.value.tasks shouldBe List(
      StubTask("upload", Region("region"), stack = Some(NamedStack("stack"))),
      StubTask("deploy", Region("region"), stack = Some(NamedStack("stack")))
    )
  }

  "resolve" should "produce a deployment task with multiple regions" in {
    val deploymentTask = TaskResolver.resolve(
      deployment = Deployment("test", "stub-package-type", List("stack"), List("region-one", "region-two"),
        Some(List("uploadArtifact", "deploy")), "app", "directory", Nil, Map("bucket" -> JsString("bucketName"))),
      deploymentResources = DeploymentResources(reporter, stubLookup(), artifactClient),
      parameters = DeployParameters(Deployer("Test user"), Build("test-project", "1"), Stage("PROD")),
      deploymentTypes = deploymentTypes,
      artifact = S3YamlArtifact("artifact-bucket", "/path/to/test-project/1")
    )

    deploymentTask.right.value.name shouldBe "test [uploadArtifact, deploy] => {region-one,region-two}/stack"
    deploymentTask.right.value.tasks shouldBe List(
      StubTask("upload", Region("region-one"), stack = Some(NamedStack("stack"))),
      StubTask("deploy", Region("region-one"), stack = Some(NamedStack("stack"))),
      StubTask("upload", Region("region-two"), stack = Some(NamedStack("stack"))),
      StubTask("deploy", Region("region-two"), stack = Some(NamedStack("stack")))
    )
  }

  "resolve" should "produce an error when the deployment type isn't found" in {
    val deploymentTask = TaskResolver.resolve(
      deployment = Deployment("test", "autoscaling", List("stack"), List("region"), Some(List("uploadArtifact", "deploy")), "app", "directory", Nil, Map("bucket" -> JsString("bucketName"))),
      deploymentResources = DeploymentResources(reporter, stubLookup(), artifactClient),
      parameters = DeployParameters(Deployer("Test user"), Build("test-project", "1"), Stage("PROD")),
      deploymentTypes = List(),
      artifact = S3YamlArtifact("artifact-bucket", "/path/to/test-project/1")
    )

    deploymentTask.left.value.context shouldBe "test"
    deploymentTask.left.value.message shouldBe "Deployment type autoscaling not found"
  }
}
