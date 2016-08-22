package magenta


import java.util.UUID

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{ListObjectsV2Request, ListObjectsV2Result}
import magenta.artifact.{S3Artifact, S3Package}
import magenta.fixtures.{StubDeploymentType, StubTask, _}
import magenta.json._
import magenta.tasks.{S3Upload, Task, TaskGraph}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}


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

    val taskList: List[Task] = TaskGraph.toTaskList(tasks)
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
    TaskGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on the_host", Some(host))
    ))
  }

  it should "only generate tasks for hosts that have apps" in {
    val taskGraph = Resolver.resolve(project(baseRecipe),
      stubLookup(Host("other_host").app(App("other_app")) +: lookupSingleHost.hosts.all), parameters(baseRecipe), reporter, artifactClient)
    TaskGraph.toTaskList(taskGraph) should be (List(
        StubTask("init_action_one per app task"),
        StubTask("action_one per host task on the_host", Some(host))
    ))
  }

  it should "generate tasks for all hosts with app" in {
    val taskGraph = Resolver.resolve(project(baseRecipe), lookupTwoHosts, parameters(baseRecipe), reporter, artifactClient)
    TaskGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on host1", Some(host1)),
      StubTask("action_one per host task on host2", Some(host2))
    ))
  }

  it should "generate tasks only for hosts with app per action" in {
    val multiRoleRecipe = {
      val doubleAppPackage = stubPackage.copy(pkgApps = Seq(app1, app2))
      val appTwoPackage = stubPackage.copy(pkgApps = Seq(app2))

      val doubleAppPackageType = stubPackageType(Seq("init_action_one"), Seq("action_two"))
      val appTwoPackageType = stubPackageType(Seq(), Seq("action_three"))

      Recipe("two",
        actionsBeforeApp = doubleAppPackageType.mkAction("init_action_one")(doubleAppPackage) :: Nil,
        actionsPerHost = basePackageType.mkAction("action_one")(stubPackage) ::
          doubleAppPackageType.mkAction("action_two")(doubleAppPackage) ::
          appTwoPackageType.mkAction("action_three")(appTwoPackage) :: Nil,
        dependsOn = Nil)
    }
    val host2WithApp2 = Host("host2", stage = CODE.name, tags = Map("group" -> "")).app(app2)
    val lookupMultiHost = stubLookup(List(host1, host2WithApp2))

    val taskGraph = Resolver.resolve(project(multiRoleRecipe), lookupMultiHost, parameters(multiRoleRecipe), reporter, artifactClient)
    TaskGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on host1", Some(host1)),
      StubTask("action_two per host task on host1", Some(host1)),
      StubTask("action_two per host task on host2", Some(host2WithApp2)),
      StubTask("action_three per host task on host2", Some(host2WithApp2))
    ))
  }

  it should "resolve all actions for a given host before moving on to the next host" in {
    val allOnAllPackageType = stubPackageType(Seq("init_action_one"), Seq("action_one", "action_two"))
    val recipe = Recipe("all",
      actionsBeforeApp = allOnAllPackageType.mkAction("init_action_one")(stubPackage) :: Nil,
      actionsPerHost = allOnAllPackageType.mkAction("action_one")(stubPackage) ::
        allOnAllPackageType.mkAction("action_two")(stubPackage) :: Nil
    )

    val taskGraph = Resolver.resolve(project(recipe), lookupTwoHosts, parameters(recipe), reporter, artifactClient)
    TaskGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on host1", Some(host1)),
      StubTask("action_two per host task on host1", Some(host1)),
      StubTask("action_one per host task on host2", Some(host2)),
      StubTask("action_two per host task on host2", Some(host2))
    ))
  }

  it should "prepare dependsOn actions correctly" in {
    val basePackageType = stubPackageType(Seq("main_init_action"), Seq("main_action"))

    val mainRecipe = Recipe("main",
      actionsBeforeApp = basePackageType.mkAction("main_init_action")(stubPackage) :: Nil,
      actionsPerHost = basePackageType.mkAction("main_action")(stubPackage) :: Nil,
      dependsOn = List("one"))

    val taskGraph = Resolver.resolve(project(mainRecipe, baseRecipe), lookupSingleHost, parameters(mainRecipe), reporter, artifactClient)
    TaskGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on the_host", Some(host)),
      StubTask("main_init_action per app task"),
      StubTask("main_action per host task on the_host", Some(host))
    ))

  }

  it should "only include dependencies once" in {
    val basePackageType = stubPackageType(Seq("main_init_action", "init_action_two"), Seq("main_action", "action_two"))

    val indirectDependencyRecipe = Recipe("two",
      actionsBeforeApp = basePackageType.mkAction("init_action_two")(stubPackage) :: Nil,
      actionsPerHost = basePackageType.mkAction("action_two")(stubPackage) :: Nil,
      dependsOn = List("one"))
    val mainRecipe = Recipe("main",
      actionsBeforeApp = basePackageType.mkAction("main_init_action")(stubPackage) :: Nil,
      actionsPerHost = basePackageType.mkAction("main_action")(stubPackage) :: Nil,
      dependsOn = List("two", "one"))

    val taskGraph = Resolver.resolve(project(mainRecipe, indirectDependencyRecipe, baseRecipe), lookupSingleHost, parameters(mainRecipe), reporter, artifactClient)
    TaskGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on the_host", Some(host)),
      StubTask("init_action_two per app task"),
      StubTask("action_two per host task on the_host", Some(host)),
      StubTask("main_init_action per app task"),
      StubTask("main_action per host task on the_host", Some(host))
    ))
  }

  it should "disable the recipe if no hosts found and actions require some" in {
    val recipeTasks = Resolver.resolveDetail(project(baseRecipe), stubLookup(List()), parameters(baseRecipe), reporter, artifactClient)
    recipeTasks.length should be(1)
    recipeTasks.head.disabled should be(true)
  }

  it should "not throw an exception if no hosts found and only whole app recipes" in {
    val nonHostRecipe = Recipe("nonHostRecipe",
      actionsBeforeApp =  basePackageType.mkAction("init_action_one")(stubPackage) :: Nil,
      dependsOn = Nil)

    Resolver.resolve(project(nonHostRecipe), stubLookup(List()), parameters(nonHostRecipe), reporter, artifactClient)
  }

  it should "only resolve tasks on hosts in the correct stage" in {
    val taskGraph = Resolver.resolve(
      project(baseRecipe),
      stubLookup(List(host, Host("host_in_other_stage", Set(app1), "other_stage"))),
      parameters(baseRecipe),
      reporter,
      artifactClient
    )
    TaskGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on the_host", Some(host))
    ))
  }

  it should "observe ordering of hosts in deployInfo" in {
    val taskGraph = Resolver.resolve(project(baseRecipe), stubLookup(List(host2, host1)), parameters(baseRecipe), reporter, artifactClient)
    TaskGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on host2", Some(host2)),
      StubTask("action_one per host task on host1", Some(host1))
    ))
  }

  it should "observe ordering of hosts in deployInfo irrespective of connection user" in {
    val pkgTypeWithUser = StubDeploymentType(
      perHostActions = {
        case "deploy" => pkg => (reporter, host, keyRing) =>
          List(StubTask("with conn", Some(host as "user")), StubTask("without conn", Some(host)))
      }
    )
    val pkg = stubPackage.copy(pkgApps = Seq(app1, app2))
    val recipe = Recipe("with-user",
      actionsPerHost = List(pkgTypeWithUser.mkAction("deploy")(pkg)))

    val taskGraph = Resolver.resolve(project(recipe), stubLookup(List(host2, host1)), parameters(recipe), reporter, artifactClient)
    TaskGraph.toTaskList(taskGraph) should be (List(
      StubTask("with conn", Some(host2 as "user")),
      StubTask("without conn", Some(host2)),
      StubTask("with conn", Some(host1 as "user")),
      StubTask("without conn", Some(host1))
    ))
  }

  it should "resolve tasks from multiple stacks" in {
    val pkgType = StubDeploymentType(
      perAppActions = {
        case "deploy" => pkg => (resources, target) => List(StubTask("stacked", stack = Some(target.stack)))
      }
    )
    val recipe = Recipe("stacked",
      actionsPerHost = List(pkgType.mkAction("deploy")(stubPackage)))

    val proj = project(recipe, NamedStack("foo"), NamedStack("bar"), NamedStack("monkey"), NamedStack("litre"))
    val taskGraph = Resolver.resolve(proj, stubLookup(), parameters(recipe), reporter, artifactClient)
    TaskGraph.toTaskList(taskGraph, proj.defaultStacks) should be (List(
      StubTask("stacked", stack = Some(NamedStack("foo"))),
      StubTask("stacked", stack = Some(NamedStack("bar"))),
      StubTask("stacked", stack = Some(NamedStack("monkey"))),
      StubTask("stacked", stack = Some(NamedStack("litre")))
    ))
  }

  def parameters(recipe: Recipe) =
    DeployParameters(Deployer("Tester"), Build("project", "build"), CODE, RecipeName(recipe.name))
}