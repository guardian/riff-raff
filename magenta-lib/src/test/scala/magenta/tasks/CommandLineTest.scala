package magenta
package tasks

import java.io.IOException
import java.util.UUID

import magenta.fixtures._
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.mutable.ListBuffer


class CommandLineTest extends FlatSpec with Matchers {

  "CommandLine" should "return sensible description for simple commands" in {
    CommandLine(List("ls", "-l")).quoted should be ("ls -l")
  }

  it should "return quoted description for commands with string params with spaces" in {
    CommandLine(List("echo", "this needs to be quoted")).quoted should
      be ("echo \"this needs to be quoted\"")
  }

  it should "execute command and pipe progress results to Logger" in {
    val recordedMessages = new ListBuffer[List[Message]]()
    MessageBroker.messages.filter(_.stack.deployParameters == Some(parameters)).subscribe(recordedMessages += _.stack.messages)

    MessageBroker.deployContext(UUID.randomUUID(), parameters) {
      val c = CommandLine(List("echo", "hello"))
      c.run()
    }

    recordedMessages.toList should be (
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
    an[IOException] should be thrownBy {
      MessageBroker.deployContext(UUID.randomUUID(), parameters) {
        CommandLine(List("unknown_command")).run()
      }
    }
  }

  it should "throw when command returns non zero exit code" in {
    a[FailException] should be thrownBy {
      MessageBroker.deployContext(UUID.randomUUID(), parameters) {
        CommandLine(List("false")).run()
      }
    }
  }

  val parameters = DeployParameters(Deployer("tester"), Build("Project","1"), CODE, RecipeName(baseRecipe.name))
  val context = DeployContext(parameters, project(baseRecipe), lookupSingleHost)

}