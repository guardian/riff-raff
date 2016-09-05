package deployment

import java.util.UUID

import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.agent.Agent
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import magenta.graph.{Deployment, MidNode, Graph}
import magenta.tasks.Task
import org.scalatest.{FlatSpecLike, ShouldMatchers}

import scala.concurrent.ExecutionContext.Implicits.global

class DeployGroupRunnerTest extends TestKit(ActorSystem("DeployGroupRunnerTest")) with FlatSpecLike with ShouldMatchers {
  import Fixtures._
  "DeployRunState" should "initalise the state from a set of tasks" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    dr.ul.allDeployments.size should be(1)
    dr.ul.isExecuting should be(false)
  }

  it should "request the first task from a simple list is run" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    dr.ref ! DeployGroupRunner.StartDeployment
    val runDeployment = dr.deploymentRunnerProbe.expectMsgClass(classOf[DeploymentRunner.RunDeployment])
    val firstDeployment = runDeployment.deployment
    firstDeployment should be(Deployment(threeSimpleTasks, "test"))
    dr.ul.executing should contain(MidNode(firstDeployment, 1))
  }

  it should "process a list of tasks and clean up" in {
    val dr = createDeployRunner()
    prepare(dr, threeSimpleTasks)
    dr.ref ! DeployGroupRunner.StartDeployment
    val runDeployment = dr.deploymentRunnerProbe.expectMsgClass(classOf[DeploymentRunner.RunDeployment])
    val firstDeployment = runDeployment.deployment
    firstDeployment should be(Deployment(threeSimpleTasks, "test"))
    dr.deploymentRunnerProbe.reply(DeployGroupRunner.DeploymentCompleted(firstDeployment))
    dr.deploymentRunnerProbe.expectNoMsg()
    dr.deployCoordinatorProbe.expectMsgClass(classOf[DeployCoordinator.CleanupDeploy])
  }

  it should "correctly process a graph" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, simpleGraph)
    val firstDeployments = dr.ul.firstDeployments
    firstDeployments.size should be(2)
    firstDeployments should contain(MidNode(Deployment(twoTasks, "branch one"), 1))
    firstDeployments should contain(MidNode(Deployment(twoTasks, "branch two"), 2))
    dr.ul.markComplete(firstDeployments.head.value)
    val nextResult = dr.ul.nextDeployments(firstDeployments.head.value)
    nextResult should be(DeployGroupRunner.FinishPath)
    dr.ul.markComplete(firstDeployments.tail.head.value)
    val nextResult2 = dr.ul.nextDeployments(firstDeployments.tail.head.value)
    nextResult2 should be(DeployGroupRunner.FinishDeploy)
  }

  it should "mark a state and task as executing" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    dr.ref ! DeployGroupRunner.StartDeployment
    val firstDeployments = dr.ul.firstDeployments
    firstDeployments.size should be(1)
    dr.ul.isExecuting should be(true)
    dr.ul.executing.size should be(1)
    dr.ul.executing should be(firstDeployments.toSet)
  }

  it should "mark a task as completed" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    dr.ref ! DeployGroupRunner.StartDeployment
    val runDeployment = dr.deploymentRunnerProbe.expectMsgClass(classOf[DeploymentRunner.RunDeployment])
    dr.deploymentRunnerProbe.reply(DeployGroupRunner.DeploymentCompleted(runDeployment.deployment))
    dr.ul.isExecuting should be(false)
    dr.ul.executing should be(Set.empty)
    dr.ul.completed should be(Set(MidNode(runDeployment.deployment, 1)))
  }

  it should "mark a task as failed" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasks)
    dr.ref ! DeployGroupRunner.StartDeployment
    val runDeployment = dr.deploymentRunnerProbe.expectMsgClass(classOf[DeploymentRunner.RunDeployment])
    dr.deploymentRunnerProbe.reply(DeployGroupRunner.DeploymentFailed(runDeployment.deployment, new RuntimeException("test exception")))
    dr.ul.isExecuting should be(false)
    dr.ul.executing should be(Set.empty)
    dr.ul.failed should be(Set(MidNode(runDeployment.deployment, 1)))
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
      Props(classOf[DeployGroupRunner], record, deployCoordinatorProbe.ref, deploymentRunnerFactory, stopFlagAgent),
      name=s"DeployGroupRunner-${record.uuid.toString}"
    )
    DRImpl(record, deployCoordinatorProbe, deploymentRunnerProbe, ref, stopFlagAgent)
  }

  case class DRwithUnderlying(deployCoordinatorProbe: TestProbe, deploymentRunnerProbe: TestProbe, ref: ActorRef, stopFlagAgent: Agent[Map[UUID, String]], ul: DeployGroupRunner) extends DR {
    val record = ul.record
  }

  def createDeployRunnerWithUnderlying(): DRwithUnderlying = {
    val deployCoordinatorProbe = TestProbe()
    val deploymentRunnerProbe = TestProbe()
    val deploymentRunnerFactory = (context: ActorRefFactory, name: String) => deploymentRunnerProbe.ref
    val stopFlagAgent = Agent(Map.empty[UUID, String])
    val record = createRecord()
    val ref = TestActorRef(
      new DeployGroupRunner(record, deployCoordinatorProbe.ref, deploymentRunnerFactory, stopFlagAgent),
      name=s"DeployGroupRunner-${record.uuid.toString}"
    )
    DRwithUnderlying(deployCoordinatorProbe, deploymentRunnerProbe, ref, stopFlagAgent, ref.underlyingActor)
  }

  def prepare(dr: DR, tasks: List[Task]): Unit = {
    val context = createContext(tasks, dr.record.uuid, dr.record.parameters)
    dr.ref ! DeployGroupRunner.ContextCreated(context)
  }

  def prepare(dr: DR, deployments: Graph[Deployment]): Unit = {
    val context = createContext(deployments, dr.record.uuid, dr.record.parameters)
    dr.ref ! DeployGroupRunner.ContextCreated(context)
  }

}
