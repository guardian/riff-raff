package magenta


import json._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import tasks._
import java.io.File
import com.decodified.scalassh.PublicKeyLogin

class ResolverTest extends FlatSpec with ShouldMatchers {

  val CODE = Stage("CODE")

  val simpleExample = """
  {
    "packages":{
      "htmlapp":{ "type":"file", "apps":["apache"]  }
    },
    "recipes":{
      "all":{
        "default":true,
        "depends":["index-build-only","api-only"]
      },
      "htmlapp-only":{
        "actions":["htmlapp.deploy"],
      }
    }
  }
"""

  "resolver" should "parse json into actions that can be executed" in {
    val parsed = JsonReader.parse(simpleExample, new File("/tmp"))
    val deployRecipe = parsed.recipes("htmlapp-only")

    val host = Host("host1").app("apache")
    val deployinfo = host :: Nil

    val tasks = Resolver.resolve(project(deployRecipe), deployinfo, parameters(deployRecipe))

    tasks.size should be (1)
    tasks should be (List(
      CopyFile(host, "/tmp/packages/htmlapp", "/")
    ))
  }

  case class StubTask(description: String) extends Task {
    def taskHosts = Nil
    def execute(keyRing: KeyRing) { }
    def verbose = "stub(%s)" format description
  }

  case class StubPerHostAction(description: String, apps: Set[App]) extends PerHostAction {
    def resolve(host: Host) = StubTask(description + " per host task on " + host.name) :: Nil
  }

  case class StubPerAppAction(description: String, apps: Set[App]) extends PerAppAction {
    def resolve(parameters: DeployParameters) = StubTask(description + " per app task") :: Nil
  }

  val app1 = App("the_role")
  val app2 = App("the_2nd_role")

  val baseRecipe = Recipe("one",
    actionsBeforeApp = StubPerAppAction("init_action_one", Set(app1)) :: Nil,
    actionsPerHost = StubPerHostAction("action_one", Set(app1)) :: Nil,
    dependsOn = Nil)

  val multiRoleRecipe = Recipe("two",
    actionsBeforeApp = StubPerAppAction("init_action_one", Set(app1, app2)) :: Nil,
    actionsPerHost = StubPerHostAction("action_one", Set(app1)) ::
      StubPerHostAction("action_two", Set(app1, app2)) ::
      StubPerHostAction("action_three", Set(app2)) :: Nil,
    dependsOn = Nil)

  val deployinfoSingleHost = List(Host("the_host").app(app1))

  val deployinfoTwoHosts =
    List(Host("host_one").app(app1), Host("host_two").app(app1))

  val deployInfoMultiHost =
    List(Host("host_one").app(app1), Host("host_two").app(app2))


  it should "generate the tasks from the actions supplied" in {
    Resolver.resolve(project(baseRecipe), deployinfoSingleHost, parameters(baseRecipe)) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on the_host")
    ))
  }

  it should "only generate tasks for hosts that have apps" in {
    Resolver.resolve(project(baseRecipe),
      Host("other_host").app("other_app") :: deployinfoSingleHost, parameters(baseRecipe)) should be (List(
        StubTask("init_action_one per app task"),
        StubTask("action_one per host task on the_host")
    ))
  }

  it should "generate tasks for all hosts with app" in {
    Resolver.resolve(project(baseRecipe), deployinfoTwoHosts, parameters(baseRecipe)) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on host_one"),
      StubTask("action_one per host task on host_two")
    ))
  }

  it should "generate tasks only for hosts with app per action" in {
    Resolver.resolve(project(multiRoleRecipe), deployInfoMultiHost, parameters(multiRoleRecipe)) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on host_one"),
      StubTask("action_two per host task on host_one"),
      StubTask("action_two per host task on host_two"),
      StubTask("action_three per host task on host_two")
    ))
  }

  it should "resolve all actions for a given host before moving on to the next host" in {
    val recipe = Recipe("multi-action",
      actionsBeforeApp = StubPerAppAction("init_action_one", Set(app1)) :: Nil,
      actionsPerHost =
        StubPerHostAction("action_one", Set(app1)) ::
        StubPerHostAction("action_two", Set(app1)) ::
        Nil
    )

    Resolver.resolve(project(recipe), deployinfoTwoHosts, parameters(recipe)) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on host_one"),
      StubTask("action_two per host task on host_one"),
      StubTask("action_one per host task on host_two"),
      StubTask("action_two per host task on host_two")
    ))
  }

  it should "prepare dependsOn actions correctly" in {
    val prereq = Recipe("prereq",
      actionsBeforeApp = StubPerAppAction("init_action_one", Set(app1)) :: Nil,
      actionsPerHost = StubPerHostAction("prereq_action", Set(app1)) :: Nil)

    val mainRecipe = Recipe("main",
      actionsBeforeApp = StubPerAppAction("main_init_action", Set(app1)) :: Nil,
      actionsPerHost = StubPerHostAction("main_action", Set(app1)) :: Nil,
      dependsOn = List("prereq"))

    Resolver.resolve(project(mainRecipe, prereq), deployinfoSingleHost, parameters(mainRecipe)) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("prereq_action per host task on the_host"),
      StubTask("main_init_action per app task"),
      StubTask("main_action per host task on the_host")
    ))

  }
  
  it should "provide a list of all apps for a recipe" in  {
    val recipe = Recipe("recipe", actionsPerHost = StubPerHostAction("action", Set(app1)) :: Nil)
    
    Resolver.possibleApps(project(recipe), recipe.name) should be (app1.name)
  }

  it should "throw an exception if no hosts found and actions require some" in {
    intercept[NoHostsFoundException] {
      Resolver.resolve(project(baseRecipe), List(), parameters(baseRecipe))
    }
  }

  it should "not throw an exception if no hosts found and only whole app recipes" in {
    val nonHostRecipe = Recipe("nonHostRecipe",
      actionsBeforeApp = StubPerAppAction("init_action_one", Set(app1)) :: Nil,
      dependsOn = Nil)

    Resolver.resolve(project(nonHostRecipe), List(), parameters(nonHostRecipe))
  }


  def project(recipes: Recipe*) = Project(Map.empty, recipes.map(r => r.name -> r).toMap)

  def parameters(recipe: Recipe) =
    DeployParameters(Deployer("Tester"), Build("project", "build"), CODE, RecipeName(recipe.name))
}