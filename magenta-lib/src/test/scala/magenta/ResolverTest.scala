package magenta

import java.util.UUID

import com.amazonaws.regions.RegionUtils
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{ListObjectsV2Request, ListObjectsV2Result}
import magenta.artifact.{S3JsonArtifact, S3Path}
import magenta.deployment_type.{Action, DeploymentType, S3}
import magenta.fixtures.{StubDeploymentType, StubTask, _}
import magenta.graph.{DeploymentGraph, DeploymentTasks, StartNode, ValueNode}
import magenta.json._
import magenta.tasks.{S3Upload, Task}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.language.existentials

class ResolverTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient = mock[AmazonS3Client]
  when(artifactClient.listObjectsV2(any[ListObjectsV2Request])).thenReturn(new ListObjectsV2Result())
  val region = Region("eu-west-1")
  val deploymentTypes = Seq(S3)

  val simpleExample = """
  {
    "stack": "web",
    "packages":{
      "htmlapp":{ "type":"aws-s3", "data":{"bucket":"test", "cacheControl":[]} }
    },
    "recipes":{
      "all":{
        "default":true,
        "depends":["index-build-only","api-only"]
      },
      "htmlapp-only":{
        "actions":["htmlapp.uploadStaticFiles"]
      }
    }
  }
                      """

  "resolver" should "parse json into actions that can be executed" in {
    val parsed = JsonReader.parse(simpleExample, new S3JsonArtifact("artifact-bucket","tmp/123"), deploymentTypes)
    val deployRecipe = parsed.recipes("htmlapp-only")

    val host = Host("host1", stage = CODE.name, tags=Map("group" -> "")).app(App("apache"))
    val lookup = stubLookup(host :: Nil)

    val resources = DeploymentResources(reporter, lookup, artifactClient)
    val tasks = Resolver.resolve(project(deployRecipe), parameters(deployRecipe), resources, region)

    val taskList: List[Task] = DeploymentGraph.toTaskList(tasks)
    taskList.size should be (1)
    taskList should be (List(
      S3Upload(Region("eu-west-1"), "test", Seq((new S3Path("artifact-bucket","tmp/123/packages/htmlapp"), "CODE/htmlapp")), publicReadAcl = true)
    ))
  }

  val app2 = App("the_2nd_role")

  val host = Host("the_host", stage = CODE.name).app(app1)

  val host1 = Host("host1", stage = CODE.name).app(app1)
  val host2 = Host("host2", stage = CODE.name).app(app1)

  val lookupTwoHosts = stubLookup(List(host1, host2))
  val resources = DeploymentResources(reporter, lookupSingleHost, artifactClient)

  it should "generate the tasks from the actions supplied" in {
    val taskGraph = Resolver.resolve(project(baseRecipe), parameters(baseRecipe), resources, region)
    DeploymentGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task number one", Region("eu-west-1")),
      StubTask("init_action_one per app task number two", Region("eu-west-1"))
    ))
  }

  it should "prepare dependsOn actions correctly" in {
    val basePackageType = stubDeploymentType(Seq("main_init_action"))

    val mainRecipe = Recipe("main",
      deploymentSteps = basePackageType.mkDeploymentStep("main_init_action")(stubPackage(basePackageType)) :: Nil,
      dependsOn = List("one"))

    val resources = DeploymentResources(reporter, lookupSingleHost, artifactClient)
    val taskGraph = Resolver.resolve(project(mainRecipe, baseRecipe), parameters(mainRecipe), resources, region)
    DeploymentGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task number one", Region("eu-west-1")),
      StubTask("init_action_one per app task number two", Region("eu-west-1")),
      StubTask("main_init_action per app task number one", Region("eu-west-1")),
      StubTask("main_init_action per app task number two", Region("eu-west-1"))
    ))

  }

  it should "only include dependencies once" in {
    val basePackageType = stubDeploymentType(Seq("main_init_action", "init_action_two"))

    val indirectDependencyRecipe = Recipe("two",
      deploymentSteps = basePackageType.mkDeploymentStep("init_action_two")(stubPackage(basePackageType)) :: Nil,
      dependsOn = List("one"))
    val mainRecipe = Recipe("main",
      deploymentSteps = basePackageType.mkDeploymentStep("main_init_action")(stubPackage(basePackageType)) :: Nil,
      dependsOn = List("two", "one"))

    val resources = DeploymentResources(reporter, lookupSingleHost, artifactClient)
    val taskGraph = Resolver.resolve(project(mainRecipe, indirectDependencyRecipe, baseRecipe), parameters(mainRecipe), resources, region)
    DeploymentGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task number one", Region("eu-west-1")),
      StubTask("init_action_one per app task number two", Region("eu-west-1")),
      StubTask("init_action_two per app task number one", Region("eu-west-1")),
      StubTask("init_action_two per app task number two", Region("eu-west-1")),
      StubTask("main_init_action per app task number one", Region("eu-west-1")),
      StubTask("main_init_action per app task number two", Region("eu-west-1"))
    ))
  }

  it should "not throw an exception if no hosts found and only whole app recipes" in {
    val nonHostRecipe = Recipe("nonHostRecipe",
      deploymentSteps =  basePackageType.mkDeploymentStep("init_action_one")(stubPackage(basePackageType)) :: Nil,
      dependsOn = Nil)

    val resources = DeploymentResources(reporter, stubLookup(List()), artifactClient)
    Resolver.resolve(project(nonHostRecipe), parameters(nonHostRecipe), resources, region)
  }

  it should "resolve tasks from multiple stacks" in {
    val pkgType = StubDeploymentType(
      actionsMap = Map("deploy" -> Action("deploy")((pkg, resources, target) => List(StubTask("stacked", Region("eu-west-1"), stack = Some(target.stack))))(StubActionRegister)),
      List("deploy")
    )
    val recipe = Recipe("stacked",
      deploymentSteps = List(pkgType.mkDeploymentStep("deploy")(stubPackage(pkgType))))

    val proj = project(recipe, NamedStack("foo"), NamedStack("bar"), NamedStack("monkey"), NamedStack("litre"))
    val resources = DeploymentResources(reporter, stubLookup(), artifactClient)
    val taskGraph = Resolver.resolve(proj, parameters(recipe), resources, region)
    DeploymentGraph.toTaskList(taskGraph) should be (List(
      StubTask("stacked", Region("eu-west-1"), stack = Some(NamedStack("foo"))),
      StubTask("stacked", Region("eu-west-1"), stack = Some(NamedStack("bar"))),
      StubTask("stacked", Region("eu-west-1"), stack = Some(NamedStack("monkey"))),
      StubTask("stacked", Region("eu-west-1"), stack = Some(NamedStack("litre")))
    ))
  }

  it should "resolve tasks from multiple stacks into a parallel task graph" in {
    val pkgType = StubDeploymentType(
      actionsMap = Map("deploy" -> Action("deploy"){ (pkg, resources, target) =>
        List(StubTask("stacked", Region("eu-west-1"), stack = Some(target.stack)))
      }(StubActionRegister)),
      List("deploy")
    )
    val recipe = Recipe("stacked",
      deploymentSteps = List(pkgType.mkDeploymentStep("deploy")(stubPackage(pkgType))))

    val proj = project(recipe, NamedStack("foo"), NamedStack("bar"), NamedStack("monkey"), NamedStack("litre"))
    val resources = DeploymentResources(reporter, stubLookup(), artifactClient)
    val taskGraph = Resolver.resolve(proj, parameters(recipe), resources, region)
    val successors = taskGraph.orderedSuccessors(StartNode)
    successors.size should be(4)

    successors should be(List(
      ValueNode(DeploymentTasks(List(StubTask("stacked", Region("eu-west-1"), stack = Some(NamedStack("foo")))), "project -> foo")),
      ValueNode(DeploymentTasks(List(StubTask("stacked", Region("eu-west-1"), stack = Some(NamedStack("bar")))), "project -> bar")),
      ValueNode(DeploymentTasks(List(StubTask("stacked", Region("eu-west-1"), stack = Some(NamedStack("monkey")))), "project -> monkey")),
      ValueNode(DeploymentTasks(List(StubTask("stacked", Region("eu-west-1"), stack = Some(NamedStack("litre")))), "project -> litre"))
    ))
  }

  def parameters(recipe: Recipe) =
    DeployParameters(Deployer("Tester"), Build("project", "build"), CODE, RecipeName(recipe.name))
}