package magenta

import java.util.UUID

import magenta.fixtures.{StubTask, _}
import magenta.tasks.Task
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.mutable.Buffer

class DeployContextTest extends FlatSpec with Matchers with MockitoSugar {

  it should ("resolve a set of tasks") in {
    val parameters = DeployParameters(Deployer("tester"), Build("project","1"), CODE, oneRecipeName)
    val context = DeployContext(parameters, project(baseRecipe), lookupSingleHost)
    MessageBroker.deployContext(UUID.randomUUID(), parameters) {
      context.tasks should be(List(
        StubTask("init_action_one per app task"),
        StubTask("action_one per host task on the_host", lookupSingleHost.hosts.all.headOption)
      ))
    }
  }

  it should ("send a Info and TaskList message when resolving tasks") in {
    val parameters = DeployParameters(Deployer("tester1"), Build("project","1"), CODE, oneRecipeName)

    val messages = Buffer[Message]()
    MessageBroker.messages.filter(_.stack.deployParameters == Some(parameters)).subscribe(messages += _.stack.top)

    MessageBroker.deployContext(UUID.randomUUID(), parameters) {
      val context = DeployContext(parameters, project(baseRecipe), lookupSingleHost)
    }

    messages.filter(_.getClass == classOf[Info]) should have size (1)
    messages.filter(_.getClass == classOf[TaskList]) should have size (1)
  }

  it should ("execute the task") in {
    val parameters = DeployParameters(Deployer("tester"), Build("prooecjt","1"), CODE, oneRecipeName)
    val context = DeployContext(parameters, project(baseMockRecipe), lookupSingleHost)
    context.execute()
    val task = context.tasks.head

    verify(task, times(1)).execute()
  }

  it should ("send taskStart and taskFinish messages for each task") in {
    val parameters = DeployParameters(Deployer("tester2"), Build("project","1"), CODE, oneRecipeName)
    val context = DeployContext(parameters, project(baseRecipe), lookupSingleHost)

    val start = Buffer[Message]()
    val finished = Buffer[Message]()
    MessageBroker.messages.filter(_.stack.deployParameters == Some(parameters)).subscribe(wrapper =>
      wrapper.stack.top match {
        case FinishContext(finishMessage) => finished += finishMessage
        case StartContext(startMessage) => start += startMessage
        case _ =>
      }
    )

    context.execute()

    start.filter(_.getClass == classOf[Deploy]) should have size (1)
    start.filter(_.getClass == classOf[TaskRun]) should have size (2)
    finished.filter(_.getClass == classOf[Deploy]) should have size (1)
    finished.filter(_.getClass == classOf[TaskRun]) should have size (2)
  }

  it should ("bookend the messages with startdeploy and finishdeploy messages") in {
    val parameters = DeployParameters(Deployer("tester3"), Build("Project","1"), CODE, oneRecipeName)
    val context = DeployContext(parameters, project(baseRecipe), lookupSingleHost)

    val start = Buffer[Message]()
    val finished = Buffer[Message]()
    MessageBroker.messages.filter(_.stack.deployParameters == Some(parameters)).subscribe(wrapper =>
      wrapper.stack.top match {
        case FinishContext(finishMessage) => finished += finishMessage
        case StartContext(startMessage) => start += startMessage
        case _ =>
      }
    )

    context.execute()

    start.head.getClass should be(classOf[Deploy])
    finished.last.getClass should be(classOf[Deploy])
  }


  val CODE = Stage("CODE")

  case class MockStubPerHostAction(description: String, apps: Seq[App]) extends Action {
    def resolve(resourceLookup: Lookup, params: DeployParameters, stack: Stack) = {
      val task = mock[Task]
      when(task.taskHost).thenReturn(Some(resourceLookup.hosts.all.head))
      task :: Nil
    }
  }

  case class MockStubPerAppAction(description: String, apps: Seq[App]) extends Action {
    def resolve(resourceLookup: Lookup, params: DeployParameters, stack: Stack) = {
      val task = mock[Task]
      when(task.taskHost).thenReturn(None)
      task :: Nil
    }
  }

  val app1 = App("the_role")
  val app2 = App("the_2nd_role")

  val oneRecipeName = RecipeName("one")

  val basePackageType = stubPackageType(Seq("init_action_one"), Seq("action_one"))

  val baseRecipe = Recipe("one",
    actionsBeforeApp = basePackageType.mkAction("init_action_one")(stubPackage) :: Nil,
    actionsPerHost = basePackageType.mkAction("action_one")(stubPackage) :: Nil,
    dependsOn = Nil)

  val baseMockRecipe = Recipe("one",
    actionsBeforeApp = MockStubPerAppAction("init_action_one", Seq(app1)) :: Nil,
    actionsPerHost = MockStubPerHostAction("action_one", Seq(app1)) :: Nil,
    dependsOn = Nil)

  def project(recipes: Recipe*) = Project(Map.empty, recipes.map(r => r.name -> r).toMap)

}