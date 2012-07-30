package magenta

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import tasks.Task
import collection.mutable.Buffer
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import java.util.UUID

class DeployContextTest extends FlatSpec with ShouldMatchers with MockitoSugar {

  it should ("resolve a set of tasks") in {
    val parameters = DeployParameters(Deployer("tester"), Build("project","1"), CODE, oneRecipeName)
    val context = DeployContext(parameters, project(baseRecipe), deployinfoSingleHost)
    MessageBroker.deployContext(parameters) {
      context.tasks should be(List(
        StubTask("init_action_one per app task"),
        StubTask("action_one per host task on the_host")
      ))
    }
  }

  it should ("send a Info and TaskList message when resolving tasks") in {
    val parameters = DeployParameters(Deployer("tester1"), Build("project","1"), CODE, oneRecipeName)
    val context = DeployContext(parameters, project(baseRecipe), deployinfoSingleHost)

    val sink = new MessageSink {
      val messages = Buffer[Message]()
      def message(uuid: UUID, stack: MessageStack) {messages += stack.top}
    }
    MessageBroker.subscribe(new MessageSinkFilter(sink, _.deployParameters == Some(parameters)))

    MessageBroker.deployContext(parameters) {
      val tasks = context.tasks
    }

    sink.messages.filter(_.getClass == classOf[Info]) should have size (1)
    sink.messages.filter(_.getClass == classOf[TaskList]) should have size (1)
  }

  it should ("execute the task") in {
    val parameters = DeployParameters(Deployer("tester"), Build("prooecjt","1"), CODE, oneRecipeName)
    val context = DeployContext(parameters, project(baseMockRecipe), deployinfoSingleHost)
    val keyRing = mock[KeyRing]
    context.execute(keyRing)
    val task = context.tasks.head

    verify(task, times(1)).execute(keyRing)
    verifyNoMoreInteractions(task)
  }

  it should ("send taskStart and taskFinish messages for each task") in {
    val parameters = DeployParameters(Deployer("tester2"), Build("project","1"), CODE, oneRecipeName)
    val context = DeployContext(parameters, project(baseRecipe), deployinfoSingleHost)

    val sink = new MessageSink {
      val messages = Buffer[Message]()
      val finished = Buffer[Message]()
      def message(uuid: UUID, stack: MessageStack) {
        stack.top match {
          case FinishContext(finishMessage) => finished += finishMessage
          case StartContext(startMessage) => messages += startMessage
          case _ =>
        }
      }
    }
    MessageBroker.subscribe(new MessageSinkFilter(sink, _.deployParameters == Some(parameters)))

    val keyRing = mock[KeyRing]
    MessageBroker.deployContext(parameters) {
      context.execute(keyRing)
    }

    sink.messages.filter(_.getClass == classOf[Deploy]) should have size (1)
    sink.messages.filter(_.getClass == classOf[TaskRun]) should have size (2)
    sink.finished.filter(_.getClass == classOf[Deploy]) should have size (1)
    sink.finished.filter(_.getClass == classOf[TaskRun]) should have size (2)
  }

  it should ("bookend the messages with startdeploy and finishdeploy messages") in {
    val parameters = DeployParameters(Deployer("tester3"), Build("Project","1"), CODE, oneRecipeName)
    val context = DeployContext(parameters, project(baseRecipe), deployinfoSingleHost)

    val sink = new MessageSink {
      val messages = Buffer[Message]()
      val finished = Buffer[Message]()
      def message(uuid: UUID, stack: MessageStack) {
        stack.top match {
          case FinishContext(finishMessage) => finished += finishMessage
          case StartContext(startMessage) => messages += startMessage
          case _ =>
        }
      }
    }
    MessageBroker.subscribe(new MessageSinkFilter(sink, _.deployParameters == Some(parameters)))

    val keyRing = mock[KeyRing]
    MessageBroker.deployContext(parameters) {
      context.execute(keyRing)
    }

    sink.messages.head.getClass should be(classOf[Deploy])
    sink.finished.last.getClass should be(classOf[Deploy])
  }


  val CODE = Stage("CODE")

  case class StubTask(description: String) extends Task {
    def taskHosts = Nil
    def execute(keyRing: KeyRing) { }
    def verbose = "stub(%s)" format description
  }

  case class StubPerHostAction(description: String, apps: Set[App]) extends PerHostAction {
    def resolve(host: Host) = StubTask(description + " per host task on " + host.name) :: Nil
  }

  case class StubPerAppAction(description: String, apps: Set[App]) extends PerAppAction {
    def resolve(stage: Stage) = StubTask(description + " per app task") :: Nil
  }

  case class MockStubPerHostAction(description: String, apps: Set[App]) extends PerHostAction {
    def resolve(host: Host) = mock[Task] :: Nil
  }

  case class MockStubPerAppAction(description: String, apps: Set[App]) extends PerAppAction {
    def resolve(stage: Stage) = mock[Task] :: Nil
  }

  val app1 = App("the_role")
  val app2 = App("the_2nd_role")

  val oneRecipeName = RecipeName("one")

  val baseRecipe = Recipe("one",
    actionsBeforeApp = StubPerAppAction("init_action_one", Set(app1)) :: Nil,
    actionsPerHost = StubPerHostAction("action_one", Set(app1)) :: Nil,
    dependsOn = Nil)

  val baseMockRecipe = Recipe("one",
    actionsBeforeApp = MockStubPerAppAction("init_action_one", Set(app1)) :: Nil,
    actionsPerHost = MockStubPerHostAction("action_one", Set(app1)) :: Nil,
    dependsOn = Nil)

  val deployinfoSingleHost = List(Host("the_host", stage=CODE.name).app(app1))

  def project(recipes: Recipe*) = Project(Map.empty, recipes.map(r => r.name -> r).toMap)

}