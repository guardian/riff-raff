package magenta

import fixtures._
import fixtures.StubTask
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import tasks.Task
import collection.mutable.Buffer
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import java.util.UUID
import scala.Some


class DeployContextTest extends FlatSpec with ShouldMatchers with MockitoSugar {

  it should ("resolve a set of tasks") in {
    val parameters = DeployParameters(Deployer("tester"), Build("project","1"), CODE, oneRecipeName)
    val context = DeployContext(parameters, project(baseRecipe), deployinfoSingleHost)
    MessageBroker.deployContext(UUID.randomUUID(), parameters) {
      context.tasks should be(List(
        StubTask("init_action_one per app task"),
        StubTask("action_one per host task on the_host", deployinfoSingleHost.hosts.headOption)
      ))
    }
  }

  it should ("send a Info and TaskList message when resolving tasks") in {
    val parameters = DeployParameters(Deployer("tester1"), Build("project","1"), CODE, oneRecipeName)

    val sink = new MessageSink {
      val messages = Buffer[Message]()
      def message(wrapper: MessageWrapper) {messages += wrapper.stack.top}
    }
    MessageBroker.subscribe(new MessageSinkFilter(sink, _.deployParameters == Some(parameters)))

    MessageBroker.deployContext(UUID.randomUUID(), parameters) {
      val context = DeployContext(parameters, project(baseRecipe), deployinfoSingleHost)
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
  }

  it should ("send taskStart and taskFinish messages for each task") in {
    val parameters = DeployParameters(Deployer("tester2"), Build("project","1"), CODE, oneRecipeName)
    val context = DeployContext(parameters, project(baseRecipe), deployinfoSingleHost)

    val sink = new MessageSink {
      val messages = Buffer[Message]()
      val finished = Buffer[Message]()
      def message(wrapper: MessageWrapper) {
        wrapper.stack.top match {
          case FinishContext(finishMessage) => finished += finishMessage
          case StartContext(startMessage) => messages += startMessage
          case _ =>
        }
      }
    }
    MessageBroker.subscribe(new MessageSinkFilter(sink, _.deployParameters == Some(parameters)))

    val keyRing = mock[KeyRing]

    context.execute(keyRing)

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
      def message(wrapper: MessageWrapper) {
        wrapper.stack.top match {
          case FinishContext(finishMessage) => finished += finishMessage
          case StartContext(startMessage) => messages += startMessage
          case _ =>
        }
      }
    }
    MessageBroker.subscribe(new MessageSinkFilter(sink, _.deployParameters == Some(parameters)))

    val keyRing = mock[KeyRing]

    context.execute(keyRing)

    sink.messages.head.getClass should be(classOf[Deploy])
    sink.finished.last.getClass should be(classOf[Deploy])
  }


  val CODE = Stage("CODE")

  case class MockStubPerHostAction(description: String, apps: Set[App]) extends Action {
    def resolve(deployInfo: DeployInfo, params: DeployParameters) = {
      val task = mock[Task]
      when(task.taskHost).thenReturn(Some(deployInfo.hosts.head))
      task :: Nil
    }
  }

  case class MockStubPerAppAction(description: String, apps: Set[App]) extends Action {
    def resolve(deployInfo: DeployInfo, params: DeployParameters) = {
      val task = mock[Task]
      when(task.taskHost).thenReturn(None)
      task :: Nil
    }
  }

  val app1 = App("the_role")
  val app2 = App("the_2nd_role")

  val oneRecipeName = RecipeName("one")

  val basePackageType = stubPackageType(Seq("init_action_one"), Seq("action_one"), Set(app1))

  val baseRecipe = Recipe("one",
    actionsBeforeApp = basePackageType.mkAction("init_action_one") :: Nil,
    actionsPerHost = basePackageType.mkAction("action_one") :: Nil,
    dependsOn = Nil)

  val baseMockRecipe = Recipe("one",
    actionsBeforeApp = MockStubPerAppAction("init_action_one", Set(app1)) :: Nil,
    actionsPerHost = MockStubPerHostAction("action_one", Set(app1)) :: Nil,
    dependsOn = Nil)

  def project(recipes: Recipe*) = Project(Map.empty, recipes.map(r => r.name -> r).toMap)

}