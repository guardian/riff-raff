package deployment

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.agent.Agent
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import deployment.DeployRunner.{FinishPath, Tasks}
import magenta.tasks.{HealthcheckGrace, PathStart, S3Upload, SayHello, Task, TaskGraph, TaskReference}
import org.scalatest.{FlatSpecLike, ShouldMatchers}

import scala.concurrent.ExecutionContext.Implicits.global

class DeployRunnerTest extends TestKit(ActorSystem("DeployRunnerTest")) with FlatSpecLike with ShouldMatchers {
  import Fixtures._
  "DeployRunState" should "initalise the state from a set of tasks" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    dr.taskRunnerProbe.expectMsgClass(classOf[TaskRunner.RunTask])
    dr.ul.allTasks.size should be(3)
    dr.ul.isExecuting should be(true)
    dr.ul.executing.size should be(1)
  }

  it should "request the first task from a simple list is run" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    val runTasks = dr.taskRunnerProbe.expectMsgClass(classOf[TaskRunner.RunTask])
    dr.taskRunnerProbe.expectNoMsg()
    val firstTask = runTasks.task
    firstTask should be(TaskReference(threeSimpleTasks.head,0,"test"))
    dr.ul.executing should contain(firstTask)
  }

  it should "process a list of tasks and clean up" in {
    val dr = createDeployRunner()
    prepare(dr, threeSimpleTasks)
    val runS3uploadTask = dr.taskRunnerProbe.expectMsgClass(classOf[TaskRunner.RunTask])
    runS3uploadTask.task should matchPattern { case TaskReference(S3Upload("test-bucket", Seq(), _, _, _, _), 0, "test") => }
    dr.taskRunnerProbe.reply(DeployRunner.TaskCompleted(runS3uploadTask.reporter, runS3uploadTask.task))
    val sayHelloTask = dr.taskRunnerProbe.expectMsgClass(classOf[TaskRunner.RunTask])
    sayHelloTask.task should matchPattern { case TaskReference(SayHello(_),1,"test") => }
    dr.taskRunnerProbe.reply(DeployRunner.TaskCompleted(sayHelloTask.reporter, sayHelloTask.task))
    val healthcheckTask = dr.taskRunnerProbe.expectMsgClass(classOf[TaskRunner.RunTask])
    healthcheckTask.task should matchPattern { case TaskReference(HealthcheckGrace(1000),2,"test") => }
    dr.taskRunnerProbe.reply(DeployRunner.TaskCompleted(healthcheckTask.reporter, healthcheckTask.task))
    dr.taskRunnerProbe.expectNoMsg()
    dr.deployCoordinatorProbe.expectMsgClass(classOf[DeployCoordinator.CleanupDeploy])
  }

  it should "correctly process a graph" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, simpleGraph)
    val firstTasks = dr.ul.first.tasks
    firstTasks.size should be(2)
    firstTasks should contain((TaskReference(S3Upload("test-bucket", Seq()), 0, "branch one"), Some(PathStart("branch one", 1))))
    firstTasks should contain((TaskReference(S3Upload("test-bucket", Seq()), 0, "branch two"), Some(PathStart("branch two", 2))))
    dr.ul.markComplete(firstTasks.head._1)
    val nextResult = dr.ul.next(firstTasks.head._1)
    nextResult should matchPattern { case Tasks(List((TaskReference(HealthcheckGrace(1000), 1, "branch one"), None))) => }
    val b1t2 = nextResult.asInstanceOf[Tasks].tasks.head._1
    dr.ul.markComplete(b1t2)
    val nextResult2 = dr.ul.next(b1t2)
    nextResult2 should matchPattern { case FinishPath() => }
  }

  it should "correctly send tasks for a graph" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, simpleGraph)
    val runS3uploadTask1 = dr.taskRunnerProbe.expectMsgClass(classOf[TaskRunner.RunTask])
    val runS3uploadTask2 = dr.taskRunnerProbe.expectMsgClass(classOf[TaskRunner.RunTask])

    runS3uploadTask1.task should matchPattern {
      case TaskReference(S3Upload("test-bucket", Seq(), _, _, _, _), 0, "branch one") =>
    }
    runS3uploadTask1.reporter should not be dr.ul.rootReporter

    runS3uploadTask2.task should matchPattern {
      case TaskReference(S3Upload("test-bucket", Seq(), _, _, _, _), 0, "branch two") =>
    }
    runS3uploadTask2.reporter should not be dr.ul.rootReporter

    dr.taskRunnerProbe.reply(DeployRunner.TaskCompleted(runS3uploadTask1.reporter, runS3uploadTask1.task))

    val healthCheckTask1 = dr.taskRunnerProbe.expectMsgClass(classOf[TaskRunner.RunTask])
    healthCheckTask1.task should matchPattern {
      case TaskReference(HealthcheckGrace(1000), 1, "branch one") =>
    }
    healthCheckTask1.reporter should be(runS3uploadTask1.reporter)

    dr.taskRunnerProbe.reply(DeployRunner.TaskCompleted(healthCheckTask1.reporter, healthCheckTask1.task))
    dr.taskRunnerProbe.expectNoMsg()
  }


  it should "mark a state and task as executing" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    val firstTasks = dr.ul.first.tasks
    firstTasks.size should be(1)
    dr.ul.markExecuting(firstTasks.map(_._1).toSet)
    dr.ul.isExecuting should be(true)
    dr.ul.executing.size should be(1)
    dr.ul.executing should be(firstTasks.map(_._1).toSet)
  }

  it should "mark a task as completed" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    val firstTasks = dr.ul.first.tasks
    firstTasks.size should be(1)
    val completedTask = firstTasks.head._1
    dr.ul.markExecuting(firstTasks.map(_._1).toSet)
    dr.ul.markComplete(completedTask)
    dr.ul.isExecuting should be(false)
    dr.ul.executing should be(Set.empty)
    dr.ul.completed should be(Set(completedTask))
    dr.ul.isFinished should be(false)
  }

  it should "mark a task as failed" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    val firstTasks = dr.ul.first.tasks
    val failedTask = firstTasks.head._1
    dr.ul.markExecuting(firstTasks.map(_._1).toSet)
    dr.ul.markFailed(failedTask)
    dr.ul.isExecuting should be(false)
    dr.ul.isFinished should be(true)
    dr.ul.failed should be(dr.ul.allTasks)
  }

  it should "process a task failure" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)

    val runS3Upload = dr.taskRunnerProbe.expectMsgClass(classOf[TaskRunner.RunTask])
    runS3Upload.task.task should be(S3Upload("test-bucket", Seq()))

    dr.taskRunnerProbe.reply(
      DeployRunner.TaskFailed(runS3Upload.reporter, runS3Upload.task, new RuntimeException("Something bad happened"))
    )
    dr.deployCoordinatorProbe.expectMsgClass(classOf[DeployCoordinator.CleanupDeploy])
    dr.taskRunnerProbe.expectNoMsg()
  }

  trait DR {
    def record: Record
    def deployCoordinatorProbe: TestProbe
    def taskRunnerProbe: TestProbe
    def ref: ActorRef
  }

  case class DRImpl(record: Record, deployCoordinatorProbe: TestProbe, taskRunnerProbe: TestProbe, ref: ActorRef, stopFlagAgent: Agent[Map[UUID, String]]) extends DR

  def createDeployRunner(): DRImpl = {
    val deployCoordinatorProbe = TestProbe()
    val taskRunnerProbe = TestProbe()
    val stopFlagAgent = Agent(Map.empty[UUID, String])
    val record = createRecord()
    val ref = system.actorOf(Props(classOf[DeployRunner], record, deployCoordinatorProbe.ref, taskRunnerProbe.ref, stopFlagAgent), name=s"TaskRunner-${record.uuid.toString}")
    DRImpl(record, deployCoordinatorProbe, taskRunnerProbe, ref, stopFlagAgent)
  }

  case class DRwithUnderlying(deployCoordinatorProbe: TestProbe, taskRunnerProbe: TestProbe, ref: ActorRef, stopFlagAgent: Agent[Map[UUID, String]], ul: DeployRunner) extends DR {
    val record = ul.record
  }

  def createDeployRunnerWithUnderlying(): DRwithUnderlying = {
    val deployCoordinatorProbe = TestProbe()
    val taskRunnerProbe = TestProbe()
    val stopFlagAgent = Agent(Map.empty[UUID, String])
    val record = createRecord()
    val ref = TestActorRef(new DeployRunner(record, deployCoordinatorProbe.ref, taskRunnerProbe.ref, stopFlagAgent), name=s"TaskRunner-${record.uuid.toString}")
    DRwithUnderlying(deployCoordinatorProbe, taskRunnerProbe, ref, stopFlagAgent, ref.underlyingActor)
  }

  def prepare(dr: DR, tasks: List[Task]): Unit = {
    dr.ref ! DeployRunner.Start()
    val prepDeploy = dr.taskRunnerProbe.expectMsgClass(classOf[TaskRunner.PrepareDeploy])
    val context = createContext(tasks, prepDeploy)
    dr.taskRunnerProbe.reply(DeployRunner.DeployReady(context))
  }

  def prepare(dr: DR, tasks: TaskGraph): Unit = {
    dr.ref ! DeployRunner.Start()
    val prepDeploy = dr.taskRunnerProbe.expectMsgClass(classOf[TaskRunner.PrepareDeploy])
    val context = createContext(tasks, prepDeploy)
    dr.taskRunnerProbe.reply(DeployRunner.DeployReady(context))
  }

}
