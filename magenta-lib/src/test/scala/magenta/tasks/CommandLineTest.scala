package magenta
package tasks

import fixtures._
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
    def message(wrapper: MessageWrapper) { recorded += wrapper.stack.messages }
  }

  it should "execute command and pipe progress results to Logger" in {
    val blackBox = new RecordingSink
    MessageBroker.subscribe(new MessageSinkFilter(blackBox, _.stack.deployParameters == Some(parameters)))

    MessageBroker.deployContext(UUID.randomUUID(), parameters) {
      val c = CommandLine(List("echo", "hello"))
      c.run()
    }

    blackBox.recorded.toList should be (
      List(StartContext(Deploy(parameters))) ::
      List(StartContext(Info("$ echo hello")),Deploy(parameters)) ::
      List(CommandOutput("hello"),Info("$ echo hello"),Deploy(parameters)) ::
      List(Verbose("return value 0"),Info("$ echo hello"),Deploy(parameters)) ::
      List(FinishContext(Info("$ echo hello")), Info("$ echo hello"), Deploy(parameters)) ::
      List(FinishContext(Deploy(parameters)), Deploy(parameters)) ::
      Nil
    )
  }

  it should "throw when command is not found" in {
    evaluating {
      MessageBroker.deployContext(UUID.randomUUID(), parameters) {
        CommandLine(List("unknown_command")).run()
      }
    } should produce [IOException]
  }

  it should "throw when command returns non zero exit code" in {
    evaluating {
      MessageBroker.deployContext(UUID.randomUUID(), parameters) {
        CommandLine(List("false")).run()
      }
    } should produce [FailException]
  }

  val app2 = App("the_2nd_role")

  val parameters = DeployParameters(Deployer("tester"), Build("Project","1"), CODE, RecipeName(baseRecipe.name))
  val context = DeployContext(parameters, project(baseRecipe), deployinfoSingleHost)

}