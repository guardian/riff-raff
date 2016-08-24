package deployment

import magenta.tasks.Task
import org.scalatest.{FlatSpec, ShouldMatchers}

class DeployRunStateTest extends FlatSpec with ShouldMatchers {
  import Fixtures._
  "DeployRunState" should "correctly initalise the state from a set of tasks" in {
    val state: DeployRunState = createDeployRunState(threeSimpleTasks)
    state.allTasks.size should be(3)
    state.isExecuting should be(false)
  }

  it should "correctly return the first task from a simple list" in {
    val state = createDeployRunState(threeSimpleTasks)
    val firstTasks = state.firstTasks
    firstTasks.size should be(1)
    firstTasks.map(_.task) should be(Set(threeSimpleTasks.head))
  }

  it should "correctly mark a state and task as executing" in {
    val state = createDeployRunState(threeSimpleTasks)
    val firstTasks = state.firstTasks
    firstTasks.size should be(1)
    val newState = state.withExecuting(firstTasks)
    newState.isExecuting should be(true)
    newState.executing.size should be(1)
    newState.executing should be(firstTasks)
  }

  it should "correctly mark a task as completed" in {
    val state = createDeployRunState(threeSimpleTasks)
    val firstTasks = state.firstTasks
    firstTasks.size should be(1)
    val completedTask = firstTasks.head
    val newState = state.withExecuting(firstTasks).withCompleted(completedTask)
    newState.isExecuting should be(false)
    newState.executing should be(Set.empty)
    newState.completed should be(Set(completedTask))
    newState.isFinished should be(false)
  }

  it should "correctly mark a task as failed" in {
    val state = createDeployRunState(threeSimpleTasks)
    val firstTasks = state.firstTasks
    val failedTask = state.firstTasks.head
    val newState = state.withExecuting(firstTasks).withFailed(failedTask)
    newState.isExecuting should be(false)
    newState.isFinished should be(true)
    newState.failed should be(state.allTasks)
  }

  it should "" in {

  }

  def createDeployRunState(tasks: List[Task]): DeployRunState = {
    val record = createRecord()
    val reporter = createReporter(record)
    val context = createContext(tasks, record, reporter)
    val state = DeployRunState(record, reporter, Some(context))
    state
  }

}
