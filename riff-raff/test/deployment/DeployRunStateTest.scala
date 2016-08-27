package deployment

import deployment.DeployRunState.{FinishDeploy, FinishPath, Tasks}
import magenta.Host
import magenta.tasks.{HealthcheckGrace, PathStart, S3Upload, SayHello, Task, TaskGraph, TaskReference}
import org.scalatest.{FlatSpec, ShouldMatchers}

class DeployRunStateTest extends FlatSpec with ShouldMatchers {
  import Fixtures._
  "DeployRunState" should "initalise the state from a set of tasks" in {
    val state: DeployRunState = createDeployRunState(threeSimpleTasks)
    state.allTasks.size should be(3)
    state.isExecuting should be(false)
  }

  it should "return the first task from a simple list" in {
    val state = createDeployRunState(threeSimpleTasks)
    val firstTasks = state.first.tasks
    firstTasks.size should be(1)
    firstTasks should be(List((TaskReference(threeSimpleTasks.head,0,"test"),Some(PathStart("test", 1)))))
  }

  it should "return the next results in a simple list" in {
    var state = createDeployRunState(threeSimpleTasks)
    val firstTask = state.first.tasks.head._1
    state = state.withCompleted(firstTask)
    val nextResult = state.next(firstTask)
    nextResult should matchPattern { case Tasks(List((TaskReference(SayHello(_),1,"test"),None))) => }
    val secondTask = nextResult.asInstanceOf[Tasks].tasks.head._1
    state = state.withCompleted(secondTask)
    val nextResult2 = state.next(secondTask)
    nextResult2 should matchPattern { case Tasks(List((TaskReference(HealthcheckGrace(1000), 2, "test"), None))) => }
    val thirdTask = nextResult2.asInstanceOf[Tasks].tasks.head._1
    state = state.withCompleted(thirdTask)
    val nextResult3 = state.next(thirdTask)
    nextResult3 should matchPattern { case FinishDeploy() => }
  }

  it should "correctly process a graph" in {
    var state = createDeployRunState(simpleGraph)
    val firstTasks = state.first.tasks
    firstTasks.size should be(2)
    firstTasks should contain((TaskReference(S3Upload("test-bucket", Seq()), 0, "branch one"), Some(PathStart("branch one", 1))))
    firstTasks should contain((TaskReference(S3Upload("test-bucket", Seq()), 0, "branch two"), Some(PathStart("branch two", 2))))
    state = state.withCompleted(firstTasks.head._1)
    val nextResult = state.next(firstTasks.head._1)
    nextResult should matchPattern { case Tasks(List((TaskReference(HealthcheckGrace(1000), 1, "branch one"), None))) => }
    val b1t2 = nextResult.asInstanceOf[Tasks].tasks.head._1
    state = state.withCompleted(b1t2)
    val nextResult2 = state.next(b1t2)
    nextResult2 should matchPattern { case FinishPath() => }
  }

  it should "mark a state and task as executing" in {
    val state = createDeployRunState(threeSimpleTasks)
    val firstTasks = state.first.tasks
    firstTasks.size should be(1)
    val newState = state.withExecuting(firstTasks.map(_._1).toSet)
    newState.isExecuting should be(true)
    newState.executing.size should be(1)
    newState.executing should be(firstTasks.map(_._1).toSet)
  }

  it should "mark a task as completed" in {
    val state = createDeployRunState(threeSimpleTasks)
    val firstTasks = state.first.tasks
    firstTasks.size should be(1)
    val completedTask = firstTasks.head._1
    val newState = state.withExecuting(firstTasks.map(_._1).toSet).withCompleted(completedTask)
    newState.isExecuting should be(false)
    newState.executing should be(Set.empty)
    newState.completed should be(Set(completedTask))
    newState.isFinished should be(false)
  }

  it should "mark a task as failed" in {
    val state = createDeployRunState(threeSimpleTasks)
    val firstTasks = state.first.tasks
    val failedTask = firstTasks.head._1
    val newState = state.withExecuting(firstTasks.map(_._1).toSet).withFailed(failedTask)
    newState.isExecuting should be(false)
    newState.isFinished should be(true)
    newState.failed should be(state.allTasks)
  }

  def createDeployRunState(tasks: List[Task]): DeployRunState = {
    val record = createRecord()
    val reporter = createReporter(record)
    val context = createContext(tasks, record, reporter)
    DeployRunState(record, reporter, Some(context))
  }
  def createDeployRunState(taskGraph: TaskGraph): DeployRunState = {
    val record = createRecord()
    val reporter = createReporter(record)
    val context = createContext(taskGraph, record, reporter)
    DeployRunState(record, reporter, Some(context))
  }

}
