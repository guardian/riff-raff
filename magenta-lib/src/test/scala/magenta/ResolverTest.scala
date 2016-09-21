package magenta

import java.util.UUID

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{ListObjectsV2Request, ListObjectsV2Result}
import magenta.artifact.{S3Artifact, S3Package}
import magenta.fixtures.{StubDeploymentType, StubTask, _}
import magenta.graph.{DeploymentTasks, DeploymentGraph, MidNode, StartNode}
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
        "actions":["htmlapp.uploadStaticFiles"],
      }
    }
  }
                      """

  "resolver" should "parse json into actions that can be executed" in {
    val parsed = JsonReader.parse(simpleExample, new S3Artifact("artifact-bucket","tmp/123"))
    val deployRecipe = parsed.recipes("htmlapp-only")

    val host = Host("host1", stage = CODE.name, tags=Map("group" -> "")).app(App("apache"))
    val lookup = stubLookup(host :: Nil)

    val tasks = Resolver.resolve(project(deployRecipe), lookup, parameters(deployRecipe), reporter, artifactClient)

    val taskList: List[Task] = DeploymentGraph.toTaskList(tasks)
    taskList.size should be (1)
    taskList should be (List(
      S3Upload("test", Seq((new S3Package("artifact-bucket","tmp/123/packages/htmlapp"), "CODE/htmlapp")), publicReadAcl = true)
    ))
  }

  val app2 = App("the_2nd_role")

  val host = Host("the_host", stage = CODE.name).app(app1)

  val host1 = Host("host1", stage = CODE.name).app(app1)
  val host2 = Host("host2", stage = CODE.name).app(app1)

  val lookupTwoHosts = stubLookup(List(host1, host2))

  it should "generate the tasks from the actions supplied" in {
    val taskGraph = Resolver.resolve(project(baseRecipe), lookupSingleHost, parameters(baseRecipe), reporter, artifactClient)
    DeploymentGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task number one"),
      StubTask("init_action_one per app task number two")
    ))
  }

  it should "prepare dependsOn actions correctly" in {
    val basePackageType = stubPackageType(Seq("main_init_action"))

    val mainRecipe = Recipe("main",
      actions = basePackageType.mkAction("main_init_action")(stubPackage) :: Nil,
      dependsOn = List("one"))

    val taskGraph = Resolver.resolve(project(mainRecipe, baseRecipe), lookupSingleHost, parameters(mainRecipe), reporter, artifactClient)
    DeploymentGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task number one"),
      StubTask("init_action_one per app task number two"),
      StubTask("main_init_action per app task number one"),
      StubTask("main_init_action per app task number two")
    ))

  }

  it should "only include dependencies once" in {
    val basePackageType = stubPackageType(Seq("main_init_action", "init_action_two"))

    val indirectDependencyRecipe = Recipe("two",
      actions = basePackageType.mkAction("init_action_two")(stubPackage) :: Nil,
      dependsOn = List("one"))
    val mainRecipe = Recipe("main",
      actions = basePackageType.mkAction("main_init_action")(stubPackage) :: Nil,
      dependsOn = List("two", "one"))

    val taskGraph = Resolver.resolve(project(mainRecipe, indirectDependencyRecipe, baseRecipe), lookupSingleHost, parameters(mainRecipe), reporter, artifactClient)
    DeploymentGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task number one"),
      StubTask("init_action_one per app task number two"),
      StubTask("init_action_two per app task number one"),
      StubTask("init_action_two per app task number two"),
      StubTask("main_init_action per app task number one"),
      StubTask("main_init_action per app task number two")
    ))
  }

  it should "not throw an exception if no hosts found and only whole app recipes" in {
    val nonHostRecipe = Recipe("nonHostRecipe",
      actions =  basePackageType.mkAction("init_action_one")(stubPackage) :: Nil,
      dependsOn = Nil)

    Resolver.resolve(project(nonHostRecipe), stubLookup(List()), parameters(nonHostRecipe), reporter, artifactClient)
  }

  it should "resolve tasks from multiple stacks" in {
    val pkgType = StubDeploymentType(
      actions = {
        case "deploy" => pkg => (resources, target) => List(StubTask("stacked", stack = Some(target.stack)))
      }
    )
    val recipe = Recipe("stacked",
      actions = List(pkgType.mkAction("deploy")(stubPackage)))

    val proj = project(recipe, NamedStack("foo"), NamedStack("bar"), NamedStack("monkey"), NamedStack("litre"))
    val taskGraph = Resolver.resolve(proj, stubLookup(), parameters(recipe), reporter, artifactClient)
    DeploymentGraph.toTaskList(taskGraph) should be (List(
      StubTask("stacked", stack = Some(NamedStack("foo"))),
      StubTask("stacked", stack = Some(NamedStack("bar"))),
      StubTask("stacked", stack = Some(NamedStack("monkey"))),
      StubTask("stacked", stack = Some(NamedStack("litre")))
    ))
  }

  it should "resolve tasks from multiple stacks into a parallel task graph" in {
    val pkgType = StubDeploymentType(
      actions = {
        case "deploy" => pkg => (resources, target) => List(StubTask("stacked", stack = Some(target.stack)))
      }
    )
    val recipe = Recipe("stacked",
      actions = List(pkgType.mkAction("deploy")(stubPackage)))

    val proj = project(recipe, NamedStack("foo"), NamedStack("bar"), NamedStack("monkey"), NamedStack("litre"))
    val taskGraph = Resolver.resolve(proj, stubLookup(), parameters(recipe), reporter, artifactClient)
    val successors = taskGraph.orderedSuccessors(StartNode)
    successors.size should be(4)

    successors should be(List(
      MidNode(DeploymentTasks(List(StubTask("stacked", stack = Some(NamedStack("foo")))), "project -> foo")),
      MidNode(DeploymentTasks(List(StubTask("stacked", stack = Some(NamedStack("bar")))), "project -> bar")),
      MidNode(DeploymentTasks(List(StubTask("stacked", stack = Some(NamedStack("monkey")))), "project -> monkey")),
      MidNode(DeploymentTasks(List(StubTask("stacked", stack = Some(NamedStack("litre")))), "project -> litre"))
    ))
  }

  def parameters(recipe: Recipe) =
    DeployParameters(Deployer("Tester"), Build("project", "build"), CODE, RecipeName(recipe.name))
}