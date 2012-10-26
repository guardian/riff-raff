package magenta
package tasks

import fixtures.{StubPerAppAction, StubPerHostAction}
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import collection.mutable.ListBuffer
import java.io.IOException
import java.util.UUID


class CommandLineTest extends FlatSpec with ShouldMatchers {

  "CommandLine" should "return sensible description for simple commands" in {
    CommandLine(List("ls", "-l")).quoted should be ("ls -l")
  }

  it should "return quoted description for commands with string params with spaces" in {
    CommandLine(List("echo", "this needs to be quoted")).quoted should
      be ("echo \"this needs to be quoted\"")
  }

  class RecordingSink extends MessageSink {
    val recorded = new ListBuffer[List[Message]]()
    def message(uuid: UUID, s: MessageStack) { recorded += s.messages }
  }

  it should "execute command and pipe progress results to Logger" in {
    val blackBox = new RecordingSink
    MessageBroker.subscribe(new MessageSinkFilter(blackBox, _.deployParameters == Some(parameters)))

    MessageBroker.deployContext(parameters) {
      val c = CommandLine(List("echo", "hello"))
      c.run()
    }

    blackBox.recorded.toList should be (
      List(StartContext(Deploy(parameters))) ::
      List(StartContext(Info("$ echo hello")),Deploy(parameters)) ::
      List(CommandOutput("hello"),Info("$ echo hello"),Deploy(parameters)) ::
      List(Verbose("return value 0"),Info("$ echo hello"),Deploy(parameters)) ::
      List(FinishContext(Info("$ echo hello")), Deploy(parameters)) ::
      List(FinishContext(Deploy(parameters))) ::
      Nil
    )
  }

  it should "throw when command is not found" in {
    evaluating {
      MessageBroker.deployContext(parameters) {
        CommandLine(List("unknown_command")).run()
      }
    } should produce [IOException]
  }

  it should "throw when command returns non zero exit code" in {
    evaluating {
      CommandLine(List("false")).run()
    } should produce [FailException]
  }

  val CODE = Stage("CODE")


  val app1 = App("the_role")
  val app2 = App("the_2nd_role")

  val baseRecipe = Recipe("one",
    actionsBeforeApp = StubPerAppAction("init_action_one", Set(app1)) :: Nil,
    actionsPerHost = StubPerHostAction("action_one", Set(app1)) :: Nil,
    dependsOn = Nil)

  val deployinfoSingleHost = DeployInfo(List(Host("the_host", stage=CODE).app(app1)))

  def project(recipes: Recipe*) = Project(Map.empty, recipes.map(r => r.name -> r).toMap)

  val parameters = DeployParameters(Deployer("tester"), Build("Project","1"), CODE, RecipeName(baseRecipe.name))
  val context = DeployContext(parameters, project(baseRecipe), deployinfoSingleHost)


}