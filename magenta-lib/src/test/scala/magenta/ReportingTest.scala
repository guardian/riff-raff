package magenta

import java.util.UUID

import magenta.ContextMessage._
import org.joda.time.DateTime
import org.scalatest.{FlatSpec, Matchers}
import magenta.Message._

class ReportingTest extends FlatSpec with Matchers {

  "Deploy Report" should "build an empty report from an empty list" in {
    val time = new DateTime()
    val id = UUID.randomUUID()
    val report = DeployReport(Nil)
    report.isRunning shouldBe false
    report shouldBe EmptyDeployReport
  }

  it should "build a no-op deploy report" in {
    val stacks = startDeployWrapper :: finishDeployWrapper :: Nil
    val report = DeployReport(stacks)
    report.isRunning should be (false)
    report shouldBe tree(deployMessageId, startDeploy, finishDep)
  }


  it should "build a one-op deploy report" in {
    val stacks = startDeployWrapper :: wrapper(infoMessageId, Some(deployMessageId), stack(infoMsg, deploy)) :: finishDeployWrapper :: Nil
    val report = DeployReport(stacks)

    report.isRunning should be (false)
    report.size should be (2)
    report shouldBe tree(deployMessageId, startDeploy, finishDep, tree(infoMessageId, infoMsg))
  }

  it should "build a complex report" in {
    val report = DeployReport(messageWrappers)

    report.size should be (4)

    report.isRunning should be (false)
    report shouldBe
      tree(deployMessageId, startDeploy, finishDep,
        tree(infoMessageId, startInfo, finishInfo,
          tree(cmdOutId, cmdOut),
          tree(verboseId, verbose)
        )
      )
  }

  it should "build a partial report" in {
    val partialStacks = messageWrappers.take(5)
    val report = DeployReport(partialStacks)
    report.size should be (4)

    report.isRunning should be (true)
    report shouldBe
      tree(deployMessageId, startDeploy,
        tree(infoMessageId, startInfo, finishInfo,
          tree(cmdOutId, cmdOut),
          tree(verboseId, verbose)
        )
      )
  }

  it should "build a nested partial report" in {
    val partialStacks = messageWrappers.take(4)
    val report = DeployReport(partialStacks)
    report.size should be (4)

    report.isRunning should be (true)
    report shouldBe
      tree(deployMessageId, startDeploy,
        tree(infoMessageId, startInfo,
          tree(cmdOutId, cmdOut),
          tree(verboseId, verbose)
        )
      )
  }

  it should "render a report as text" in {
    val report = DeployReport(messageWrappers)
    report should matchPattern { case drt:DeployReportTree => }
    val tree = report.asInstanceOf[DeployReportTree]

    tree.size should be (4)

    tree.render.mkString(", ") should be (""":Deploy(DeployParameters(Deployer(Test reports),Build(test-project,1),Stage(CODE),All)) [Completed], 1:Info($ echo hello) [Completed], 1.1:CommandOutput(hello) [Not running], 1.2:Verbose(return value 0) [Not running]""")
  }

  it should "know it has failed" in {
    val failStacks = messageWrappers.take(4) ::: List(wrapper(UUID.randomUUID(), Some(infoMessageId), stack(failInfo, infoMsg, deploy)), wrapper(UUID.randomUUID(), Some(deployMessageId), stack(failDep, deploy)))
    val report = DeployReport(failStacks)
    report.size should be (4)

    report shouldBe
      tree(deployMessageId, startDeploy, failDep,
        tree(infoMessageId, startInfo, failInfo,
          tree(cmdOutId, cmdOut),
          tree(verboseId, verbose)
        )
      )
    report.isRunning should be (false)
    report.cascadeState should be(RunState.Failed)
  }

  val testTime = new DateTime()

  def stack( messages: Message * ): MessageStack = {
    stack(testTime, messages: _*)
  }

  def stack( time: DateTime, messages: Message * ): MessageStack = {
    MessageStack(messages.toList, time)
  }

  def wrapper( id: UUID, parentId: Option[UUID], stack: MessageStack ): MessageWrapper = {
    MessageWrapper(MessageContext(deployId, parameters, parentId), id, stack)
  }

  def tree( id: UUID, message: Message, trees: DeployReportTree * ): DeployReportTree = {
    DeployReportTree( MessageState(message, testTime, id), trees.toList )
  }
  def tree( id: UUID, startMessage: StartContext, finishMessage: FinishContext, trees: DeployReportTree * ): DeployReportTree = {
    DeployReportTree( MessageState(startMessage, finishMessage, testTime, id), trees.toList )
  }
  def tree( id: UUID, startMessage: StartContext, failMessage: FailContext, trees: DeployReportTree * ): DeployReportTree = {
    DeployReportTree( MessageState(startMessage, failMessage, testTime, id), trees.toList )
  }

  val parameters = DeployParameters(Deployer("Test reports"),Build("test-project","1"),Stage("CODE"))

  val deploy = Deploy(parameters)
  val startDeploy = StartContext(deploy)
  val infoMsg = Info("$ echo hello")
  val startInfo = StartContext(infoMsg)
  val cmdOut = CommandOutput("hello")
  val verbose = Verbose("return value 0")
  val finishDep = FinishContext(deploy)
  val finishInfo = FinishContext(infoMsg)
  val failMsg = Fail("Failed", new RuntimeException("Failed"))
  val failInfo = FailContext(infoMsg)
  val failDep = FailContext(deploy)
  val messageStacks: List[MessageStack] =
    stack(startDeploy) ::
      stack(startInfo, deploy) ::
      stack(cmdOut, infoMsg, deploy) ::
      stack(verbose, infoMsg, deploy) ::
      stack(finishInfo, deploy) ::
      stack(finishDep) ::
      Nil

  val deployId = UUID.randomUUID()
  val deployMessageId = UUID.randomUUID()
  val infoMessageId = UUID.randomUUID()
  val cmdOutId = UUID.randomUUID()
  val verboseId = UUID.randomUUID()
  val finishInfoId = UUID.randomUUID()
  val finishDeployId = UUID.randomUUID()

  val startDeployWrapper = wrapper(deployMessageId, None, stack(startDeploy))
  val finishDeployWrapper = wrapper(finishDeployId, Some(deployMessageId), stack(finishDep, deploy))

  val messageWrappers: List[MessageWrapper] =
    startDeployWrapper ::
    wrapper(infoMessageId, Some(deployMessageId), stack(startInfo, deploy)) ::
    wrapper(cmdOutId, Some(infoMessageId), stack(cmdOut, infoMsg, deploy)) ::
    wrapper(verboseId, Some(infoMessageId), stack(verbose, infoMsg, deploy)) ::
    wrapper(finishInfoId, Some(infoMessageId), stack(finishInfo, infoMsg, deploy)) ::
    finishDeployWrapper ::
    Nil

}
