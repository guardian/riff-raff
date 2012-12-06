package test

import scala.Some
import net.liftweb.json._
import net.liftweb.json.Diff
import org.joda.time.DateTime
import java.util.UUID
import magenta._
import deployment.{DeployV2Record, Task, DeployRecord}
import persistence.{LogDocument, DeployRecordDocument}
import java.io.File

case class RenderDiff(diff: Diff) {
  lazy val attributes = Map("changed" -> diff.changed, "added" -> diff.added, "deleted" -> diff.deleted)
  lazy val isEmpty = !attributes.values.exists(_ != JNothing)

  def renderJV(json: JValue): Option[String] = if (json == JNothing) None else Some(compact(render(json)))
  override def toString: String = {
    val jsonMap = attributes.mapValues(renderJV(_))
    jsonMap.flatMap { case (key: String, rendered: Option[String]) =>
      rendered.map{r => "%s: %s" format (key,r)}
    } mkString("\n")
  }
}

trait Utilities {
  def compareJson(from: String, to: String) = RenderDiff(Diff.diff(parse(from), parse(to)))
}

trait PersistenceTestInstances {
  val testTime = new DateTime()
  lazy val testUUID = UUID.fromString("90013e69-8afc-4ba2-80a8-d7b063183d13")
  lazy val parameters = DeployParameters(Deployer("Tester"), Build("test-project", "1"), Stage("CODE"), RecipeName("test-recipe"))
  lazy val testParamsWithHosts = parameters.copy(hostList=List("host1", "host2"))
  lazy val testRecord = DeployRecord(testTime, Task.Deploy, testUUID, parameters, messageStacks)
  lazy val testRecordV2 = DeployV2Record(testTime, Task.Deploy, testUUID, parameters, messageWrappers)
  lazy val testDocument = DeployRecordDocument(testRecord)

  lazy val comprehensiveDeployRecord = {
    val time = new DateTime(2012,11,8,17,20,00)
    val uuid = UUID.fromString("39320f5b-7837-4f47-85f7-bc2d780e19f6")
    val parameters = DeployParameters(Deployer("Tester"), Build("test::project", "1"), Stage("TEST"), RecipeName("test-recipe"), List("testhost1", "testhost2"))
    val testNestedDetail = ThrowableDetail("java.lang.RuntimeException", "Test nested exception", "Long string\n With new lines\n and line numbers:5\n etc etc etc")
    val testThrowableDetail = ThrowableDetail("java.lang.RuntimeException", "Test Exception", "Long string\n With new lines\n and line numbers:5\n etc etc etc", Some(testNestedDetail))
    val testTask = UnserialisableTask(new File("/tmp"))
    val messageStacks = List(
      MessageStack(List(StartContext(Deploy(parameters))), time),
      MessageStack(List(Info("An information message"), StartContext(Deploy(parameters))), time),
      MessageStack(List(Verbose("A verbose message"), StartContext(Deploy(parameters))), time),
      MessageStack(List(CommandOutput("Some command stdout"), StartContext(Deploy(parameters))), time),
      MessageStack(List(CommandError("Some command stderr"), StartContext(Deploy(parameters))), time),
      MessageStack(List(Fail("A failure", testThrowableDetail), StartContext(Deploy(parameters))), time),
      MessageStack(List(TaskRun(testTask), StartContext(Deploy(parameters))), time),
      MessageStack(List(TaskList(List(testTask)), StartContext(Deploy(parameters))), time),
      MessageStack(List(FailContext(Deploy(parameters), testThrowableDetail)), time),
      MessageStack(List(FinishContext(Deploy(parameters))), time)
    )
    DeployRecord(time, Task.Deploy, uuid, parameters, messageStacks)
  }

  def stack( messages: Message * ): MessageStack = {
    stack(testTime, messages: _*)
  }

  def stack( time: DateTime, messages: Message * ): MessageStack = {
    MessageStack(messages.toList, time)
  }



  val deploy = Deploy(parameters)
  val startDeploy = StartContext(deploy)
  val infoMsg = Info("$ echo hello")
  val startInfo = StartContext(infoMsg)
  val cmdOut = CommandOutput("hello")
  val verbose = Verbose("return value 0")
  val finishDep = FinishContext(deploy)
  val finishInfo = FinishContext(infoMsg)
  val failInfo = FailContext(infoMsg, new RuntimeException("Failed"))
  val failDep = FailContext(deploy, new RuntimeException("Failed"))
  val messageStacks: List[MessageStack] =
    stack(startDeploy) ::
      stack(startInfo, deploy) ::
      stack(cmdOut, infoMsg, deploy) ::
      stack(verbose, infoMsg, deploy) ::
      stack(finishInfo, deploy) ::
      stack(finishDep) ::
      Nil

  val deployMessageId = UUID.randomUUID()
  val infoMessageId = UUID.randomUUID()

  def wrapper( id: UUID, parentId: Option[UUID], stack: MessageStack ): MessageWrapper = {
    MessageWrapper(MessageContext(testUUID, parameters, parentId), id, stack)
  }

  def wrapper( parentId: Option[UUID], stack: MessageStack ): MessageWrapper = {
    MessageWrapper(MessageContext(testUUID, parameters, parentId), UUID.randomUUID(), stack)
  }

  val startDeployWrapper = wrapper(deployMessageId, None, stack(startDeploy))
  val finishDeployWrapper = wrapper(UUID.randomUUID(), Some(deployMessageId), stack(finishDep, deploy))

  val messageWrappers: List[MessageWrapper] =
    startDeployWrapper ::
      wrapper(infoMessageId, Some(deployMessageId), stack(startInfo, deploy)) ::
      wrapper(Some(infoMessageId), stack(cmdOut, infoMsg, deploy)) ::
      wrapper(Some(infoMessageId), stack(verbose, infoMsg, deploy)) ::
      wrapper(Some(infoMessageId), stack(finishInfo, infoMsg, deploy)) ::
      finishDeployWrapper ::
      Nil

  val logDocuments: List[LogDocument] = messageWrappers.map{wrapper =>
    LogDocument(wrapper.context.deployId, wrapper.messageId, wrapper.context.parentId, wrapper.stack.top, wrapper.stack.time)
  }
}