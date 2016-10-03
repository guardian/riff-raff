package deployment.actors

import java.util.UUID

import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.agent.Agent
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import deployment.{Fixtures, Record}
import magenta.graph.{DeploymentTasks, Graph, ValueNode}
import magenta.tasks.Task
import org.scalatest.{FlatSpecLike, ShouldMatchers}

import scala.concurrent.ExecutionContext.Implicits.global

class DeployGroupRunnerTest extends TestKit(ActorSystem("DeployGroupRunnerTest")) with FlatSpecLike with ShouldMatchers {
  import Fixtures._
  "DeployGroupRunnerTest" should "initalise the state from a set of tasks" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    dr.ul.allDeployments.size should be(1)
    dr.ul.isExecuting should be(false)
  }

  it should "request the first task from a simple list is run" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    dr.ref ! DeployGroupRunner.StartDeployment
    val runDeployment = dr.deploymentRunnerProbe.expectMsgClass(classOf[TasksRunner.RunDeployment])
    val firstDeployment = runDeployment.deployment
    firstDeployment should be(DeploymentTasks(threeSimpleTasks, "test"))
    dr.ul.executing should contain(ValueNode(firstDeployment))
  }

  it should "process a list of tasks and clean up" in {
    val dr = createDeployRunner()
    prepare(dr, threeSimpleTasks)
    dr.ref ! DeployGroupRunner.StartDeployment
    val runDeployment = dr.deploymentRunnerProbe.expectMsgClass(classOf[TasksRunner.RunDeployment])
    val firstDeployment = runDeployment.deployment
    firstDeployment should be(DeploymentTasks(threeSimpleTasks, "test"))
    dr.deploymentRunnerProbe.reply(DeployGroupRunner.DeploymentCompleted(firstDeployment))
    dr.deploymentRunnerProbe.expectNoMsg()
    dr.deployCoordinatorProbe.expectMsgClass(classOf[DeployCoordinator.CleanupDeploy])
  }

  it should "correctly process a graph" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, simpleGraph)
    val firstDeployments = dr.ul.first
    firstDeployments.size should be(2)
    firstDeployments should contain(ValueNode(DeploymentTasks(twoTasks, "branch one")))
    firstDeployments should contain(ValueNode(DeploymentTasks(twoTasks, "branch two")))
    dr.ul.markComplete(firstDeployments.head.value)
    val nextResult = dr.ul.next(firstDeployments.head.value)
    nextResult should be(DeployGroupRunner.DeployUnfinished)
    dr.ul.markComplete(firstDeployments.tail.head.value)
    val nextResult2 = dr.ul.next(firstDeployments.tail.head.value)
    nextResult2 should be(DeployGroupRunner.DeployFinished)
  }

  it should "mark a state and task as executing" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    dr.ref ! DeployGroupRunner.StartDeployment
    val firstDeployments = dr.ul.first
    firstDeployments.size should be(1)
    dr.ul.isExecuting should be(true)
    dr.ul.executing.size should be(1)
    dr.ul.executing should be(firstDeployments.toSet)
  }

  it should "mark a task as completed" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    dr.ref ! DeployGroupRunner.StartDeployment
    val runDeployment = dr.deploymentRunnerProbe.expectMsgClass(classOf[TasksRunner.RunDeployment])
    dr.deploymentRunnerProbe.reply(DeployGroupRunner.DeploymentCompleted(runDeployment.deployment))
    dr.ul.isExecuting should be(false)
    dr.ul.executing should be(Set.empty)
    dr.ul.completed should be(Set(ValueNode(runDeployment.deployment)))
  }

  it should "mark a task as failed" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    dr.ref ! DeployGroupRunner.StartDeployment
    val runDeployment = dr.deploymentRunnerProbe.expectMsgClass(classOf[TasksRunner.RunDeployment])
    dr.deploymentRunnerProbe.reply(DeployGroupRunner.DeploymentFailed(runDeployment.deployment, new RuntimeException("test exception")))
    dr.ul.isExecuting should be(false)
    dr.ul.executing should be(Set.empty)
    dr.ul.failed should be(Set(ValueNode(runDeployment.deployment)))
    dr.deploymentRunnerProbe.expectNoMsg()
    dr.deployCoordinatorProbe.expectMsgClass(classOf[DeployCoordinator.CleanupDeploy])
  }

  trait DR {
    def record: Record
    def deployCoordinatorProbe: TestProbe
    def deploymentRunnerProbe: TestProbe
    def ref: ActorRef
  }

  case class DRImpl(record: Record, deployCoordinatorProbe: TestProbe, deploymentRunnerProbe: TestProbe, ref: ActorRef, stopFlagAgent: Agent[Map[UUID, String]]) extends DR

  def createDeployRunner(): DRImpl = {
    val deployCoordinatorProbe = TestProbe()
    val deploymentRunnerProbe = TestProbe()
    val deploymentRunnerFactory = (context: ActorRefFactory, name: String) => deploymentRunnerProbe.ref
    val stopFlagAgent = Agent(Map.empty[UUID, String])
    val record = createRecord()
    val ref = system.actorOf(
      Props(new DeployGroupRunner(record, deployCoordinatorProbe.ref, deploymentRunnerFactory, stopFlagAgent, prismLookup = null)),
      name=s"DeployGroupRunner-${record.uuid.toString}"
    )
    DRImpl(record, deployCoordinatorProbe, deploymentRunnerProbe, ref, stopFlagAgent)
  }

  case class DRwithUnderlying(record: Record, deployCoordinatorProbe: TestProbe, deploymentRunnerProbe: TestProbe, ref: ActorRef, stopFlagAgent: Agent[Map[UUID, String]], ul: DeployGroupRunner) extends DR {
  }

  def createDeployRunnerWithUnderlying(): DRwithUnderlying = {
    val deployCoordinatorProbe = TestProbe()
    val deploymentRunnerProbe = TestProbe()
    val deploymentRunnerFactory = (context: ActorRefFactory, name: String) => deploymentRunnerProbe.ref
    val stopFlagAgent = Agent(Map.empty[UUID, String])
    val record = createRecord()
    val ref = TestActorRef(
      new DeployGroupRunner(record, deployCoordinatorProbe.ref, deploymentRunnerFactory, stopFlagAgent, prismLookup = null),
      name=s"DeployGroupRunner-${record.uuid.toString}"
    )
    DRwithUnderlying(record, deployCoordinatorProbe, deploymentRunnerProbe, ref, stopFlagAgent, ref.underlyingActor)
  }

  def prepare(dr: DR, tasks: List[Task]): Unit = {
    val context = createContext(tasks, dr.record.uuid, dr.record.parameters)
    dr.ref ! DeployGroupRunner.ContextCreated(context)
  }

  def prepare(dr: DR, deployments: Graph[DeploymentTasks]): Unit = {
    val context = createContext(deployments, dr.record.uuid, dr.record.parameters)
    dr.ref ! DeployGroupRunner.ContextCreated(context)
  }

}
