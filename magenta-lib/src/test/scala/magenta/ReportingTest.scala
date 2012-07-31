package magenta
package tasks

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec

class ReportingTest extends FlatSpec with ShouldMatchers {

  "Deploy Report" should "build an empty report from an empty list" in {
    val report = DeployReport(Nil)
    report.isRunning should be (false)
    report should be (tree(""))
  }

  it should "build a no-op deploy report" in {
    val stacks = stack(startDeploy) :: stack(finishDep) :: Nil
    val report = DeployReport(stacks)
    report.isRunning should be (false)
    report should be (tree("", tree(startDeploy, finishDep)))
  }

  it should "build a one-op deploy report" in {
    val deploy = Deploy(parameters)

    val stacks = stack(startDeploy) :: stack(infoMsg, deploy) :: stack(finishDep) :: Nil
    val report = DeployReport(stacks)

    report.isRunning should be (false)
    report.size should be (3)
    report should be (tree("", tree(startDeploy, finishDep, tree(infoMsg))))
  }

  it should "build a complex report" in {
    val report = DeployReport(messageStacks)

    report.size should be (5)

    report.isRunning should be (false)
    report should be (tree("",
       tree(startDeploy, finishDep, tree(startInfo, finishInfo, tree(cmdOut), tree(verbose)))
    ))
  }

  it should "build a partial report" in {
    val partialStacks = messageStacks.take(5)
    val report = DeployReport(partialStacks)
    report.size should be (5)

    report.isRunning should be (true)
    report should be (tree("",
      tree(startDeploy, tree(startInfo, finishInfo, tree(cmdOut), tree(verbose)))
    ))
  }

  it should "build a nested partial report" in {
    val partialStacks = messageStacks.take(4)
    val report = DeployReport(partialStacks)
    report.size should be (5)

    report.isRunning should be (true)
    report should be (tree("",
      tree(startDeploy, tree(startInfo, tree(cmdOut), tree(verbose)))
    ))
  }

  it should "add a report title" in {
    val title = "Deploy report for my test"
    val report = DeployReport(messageStacks, title)

    report.message should be(Info(title))
  }

  it should "render a report as text" in {
    val report = DeployReport(messageStacks)

    report.size should be (5)

    report.render.mkString(", ") should be (""":Info() [Completed], 1:Deploy(DeployParameters(Deployer(Test reports),Build(test-project,1),Stage(CODE),RecipeName(default),List())) [Completed], 1.1:Info($ echo hello) [Completed], 1.1.1:CommandOutput(hello) [Not running], 1.1.2:Verbose(return value 0) [Not running]""")
  }

  it should "know it has failed" in {
    val failStacks = messageStacks.take(4) ::: List(stack(failInfo, deploy), stack(failDep))
    val report = DeployReport(failStacks)
    report.size should be (5)

    report should be (tree("",
        tree(startDeploy, failDep, tree(startInfo, failInfo, tree(cmdOut), tree(verbose)))
    ))
    report.isRunning should be (false)
    report.cascadeState should be(RunState.Failed)
  }

  def stack( messages: Message * ): MessageStack = {
    MessageStack(messages.toList)
  }

  def tree( title: String, trees: ReportTree * ): ReportTree = {
    ReportTree( Report(title), trees.toList )
  }

  def tree( message: Message, trees: ReportTree * ): ReportTree = {
    ReportTree( MessageState(message), trees.toList )
  }
  def tree( startMessage: StartContext, finishMessage: FinishContext, trees: ReportTree * ): ReportTree = {
    ReportTree( MessageState(startMessage, finishMessage), trees.toList )
  }
  def tree( startMessage: StartContext, failMessage: FailContext, trees: ReportTree * ): ReportTree = {
    ReportTree( MessageState(startMessage, failMessage), trees.toList )
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

}