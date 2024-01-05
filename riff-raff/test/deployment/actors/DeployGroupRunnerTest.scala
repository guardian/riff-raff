package deployment.actors

import java.util.UUID
import org.apache.pekko.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import org.apache.pekko.agent.Agent
import org.apache.pekko.testkit.{TestActorRef, TestKit, TestProbe}
import conf.Config
import deployment.{Fixtures, Record}
import magenta.graph.{DeploymentTasks, Graph, ValueNode}
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration

import scala.concurrent.ExecutionContext.Implicits.global

class DeployGroupRunnerTest
    extends TestKit(ActorSystem("DeployGroupRunnerTest"))
    with AnyFlatSpecLike
    with Matchers {
  import Fixtures._
  "DeployGroupRunnerTest" should "initalise the state from a set of tasks" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasksGraph)
    dr.ul.reachableDeployments.size should be(1)
    dr.ul.isExecuting should be(false)
  }

  it should "request the first task from a simple list is run" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasksGraph)
    dr.ref ! DeployGroupRunner.StartDeployment
    val runDeployment = dr.deploymentRunnerProbe.expectMsgClass(
      classOf[TasksRunner.RunDeployment]
    )
    val firstDeployment = runDeployment.deployment
    firstDeployment should be(DeploymentTasks(threeSimpleTasks, "test"))
    dr.ul.executing should contain(ValueNode(firstDeployment))
  }

  it should "process a list of tasks and clean up" in {
    val dr = createDeployRunner()
    prepare(dr, threeSimpleTasksGraph)
    dr.ref ! DeployGroupRunner.StartDeployment
    val runDeployment = dr.deploymentRunnerProbe.expectMsgClass(
      classOf[TasksRunner.RunDeployment]
    )
    val firstDeployment = runDeployment.deployment
    firstDeployment should be(DeploymentTasks(threeSimpleTasks, "test"))
    dr.deploymentRunnerProbe.reply(
      DeployGroupRunner.DeploymentCompleted(firstDeployment)
    )
    dr.deploymentRunnerProbe.expectNoMsg()
    dr.deployCoordinatorProbe.expectMsgClass(
      classOf[DeployCoordinator.CleanupDeploy]
    )
  }

  it should "correctly process a graph" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, simpleGraph)
    val firstDeployments = dr.ul.first
    firstDeployments.size should be(2)
    firstDeployments should contain(
      ValueNode(DeploymentTasks(twoTasks, "branch one"))
    )
    firstDeployments should contain(
      ValueNode(DeploymentTasks(twoTasks, "branch two"))
    )
    dr.ul.markComplete(firstDeployments.head.value)
    val nextResult = dr.ul.next(firstDeployments.head.value)
    nextResult should be(DeployGroupRunner.DeployUnfinished)
    dr.ul.markComplete(firstDeployments.tail.head.value)
    val nextResult2 = dr.ul.next(firstDeployments.tail.head.value)
    nextResult2 should be(DeployGroupRunner.DeployFinished)
  }

  it should "mark a state and task as executing" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasksGraph)
    dr.ref ! DeployGroupRunner.StartDeployment
    val firstDeployments = dr.ul.first
    firstDeployments.size should be(1)
    dr.ul.isExecuting should be(true)
    dr.ul.executing.size should be(1)
    dr.ul.executing should be(firstDeployments.toSet)
  }

  it should "mark a task as completed" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasksGraph)
    dr.ref ! DeployGroupRunner.StartDeployment
    val runDeployment = dr.deploymentRunnerProbe.expectMsgClass(
      classOf[TasksRunner.RunDeployment]
    )
    dr.deploymentRunnerProbe.reply(
      DeployGroupRunner.DeploymentCompleted(runDeployment.deployment)
    )
    dr.ul.isExecuting should be(false)
    dr.ul.executing should be(Set.empty)
    dr.ul.completed should be(Set(ValueNode(runDeployment.deployment)))
  }

  it should "mark a task as failed" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, threeSimpleTasksGraph)
    dr.ref ! DeployGroupRunner.StartDeployment
    val runDeployment = dr.deploymentRunnerProbe.expectMsgClass(
      classOf[TasksRunner.RunDeployment]
    )
    dr.deploymentRunnerProbe.reply(
      DeployGroupRunner.DeploymentFailed(
        runDeployment.deployment,
        new RuntimeException("test exception")
      )
    )
    dr.ul.isExecuting should be(false)
    dr.ul.executing should be(Set.empty)
    dr.ul.failed should be(Set(ValueNode(runDeployment.deployment)))
    dr.deploymentRunnerProbe.expectNoMsg()
    dr.deployCoordinatorProbe.expectMsgClass(
      classOf[DeployCoordinator.CleanupDeploy]
    )
  }

  it should "be finished if all tasks that could potentially be run (given failures) have completed" in {
    val dr = createDeployRunnerWithUnderlying()
    prepare(dr, dependentGraph)
    dr.ref ! DeployGroupRunner.StartDeployment
    val runDeployment = dr.deploymentRunnerProbe.expectMsgClass(
      classOf[TasksRunner.RunDeployment]
    )
    val secondTaskSet = dr.deploymentRunnerProbe.expectMsgClass(
      classOf[TasksRunner.RunDeployment]
    )
    dr.deploymentRunnerProbe.reply(
      DeployGroupRunner.DeploymentFailed(
        runDeployment.deployment,
        new RuntimeException("test exception")
      )
    )
    dr.ul.isExecuting should be(true)
    dr.ul.failed should be(Set(ValueNode(runDeployment.deployment)))
    dr.ul.isFinished should be(false)
    dr.deploymentRunnerProbe.reply(
      DeployGroupRunner.DeploymentCompleted(secondTaskSet.deployment)
    )
    dr.ul.isExecuting should be(false)
    dr.ul.isFinished should be(true)
    dr.deploymentRunnerProbe.expectNoMsg()
    dr.deployCoordinatorProbe.expectMsgClass(
      classOf[DeployCoordinator.CleanupDeploy]
    )
  }

  val deploymentTypes = Nil

  trait DR {
    def record: Record
    def deployCoordinatorProbe: TestProbe
    def deploymentRunnerProbe: TestProbe
    def ref: ActorRef
  }

  case class DRImpl(
      record: Record,
      deployCoordinatorProbe: TestProbe,
      deploymentRunnerProbe: TestProbe,
      ref: ActorRef,
      stopFlagAgent: Agent[Map[UUID, String]]
  ) extends DR

  val config = new Config(
    configuration = Configuration(("test.config", "abc")).underlying,
    DateTime.now
  )

  def createDeployRunner(): DRImpl = {
    val deployCoordinatorProbe = TestProbe()
    val deploymentRunnerProbe = TestProbe()
    val deploymentRunnerFactory = (context: ActorRefFactory, name: String) =>
      deploymentRunnerProbe.ref
    val stopFlagAgent = Agent(Map.empty[UUID, String])
    val record = createRecord()
    val ref = system.actorOf(
      Props(
        new DeployGroupRunner(
          config,
          record,
          deployCoordinatorProbe.ref,
          deploymentRunnerFactory,
          stopFlagAgent,
          prismLookup = null,
          deploymentTypes,
          global
        )
      ),
      name = s"DeployGroupRunner-${record.uuid.toString}"
    )
    DRImpl(
      record,
      deployCoordinatorProbe,
      deploymentRunnerProbe,
      ref,
      stopFlagAgent
    )
  }

  case class DRwithUnderlying(
      record: Record,
      deployCoordinatorProbe: TestProbe,
      deploymentRunnerProbe: TestProbe,
      ref: ActorRef,
      stopFlagAgent: Agent[Map[UUID, String]],
      ul: DeployGroupRunner
  ) extends DR {}

  def createDeployRunnerWithUnderlying(): DRwithUnderlying = {
    val deployCoordinatorProbe = TestProbe()
    val deploymentRunnerProbe = TestProbe()
    val deploymentRunnerFactory = (context: ActorRefFactory, name: String) =>
      deploymentRunnerProbe.ref
    val stopFlagAgent = Agent(Map.empty[UUID, String])
    val record = createRecord()
    val ref = TestActorRef(
      new DeployGroupRunner(
        config,
        record,
        deployCoordinatorProbe.ref,
        deploymentRunnerFactory,
        stopFlagAgent,
        prismLookup = null,
        deploymentTypes,
        global
      ),
      name = s"DeployGroupRunner-${record.uuid.toString}"
    )
    DRwithUnderlying(
      record,
      deployCoordinatorProbe,
      deploymentRunnerProbe,
      ref,
      stopFlagAgent,
      ref.underlyingActor
    )
  }

  def prepare(dr: DR, deployments: Graph[DeploymentTasks]): Unit = {
    val context =
      createContext(deployments, dr.record.uuid, dr.record.parameters)
    dr.ref ! DeployGroupRunner.ContextCreated(context)
  }

}
