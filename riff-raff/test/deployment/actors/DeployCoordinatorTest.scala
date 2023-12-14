package deployment.actors

import java.util.UUID
import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import deployment.{Fixtures, Record}
import org.mockito.MockitoSugar
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import utils.Agent

import scala.jdk.CollectionConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global

object DeployCoordinatorTest {
  lazy val testConfig = ConfigFactory
    .parseMap(
      Map("akka.test.single-expect-default" -> "500").asJava
    )
    .withFallback(ConfigFactory.load())
}

class DeployCoordinatorTest
    extends TestKit(
      ActorSystem("DeployCoordinatorTest", DeployCoordinatorTest.testConfig)
    )
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with MockitoSugar {

  import Fixtures._

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "DeployCoordinator" should "respond to StartDeploy with Start to deploy runner" in {
    val dc = createDeployCoordinatorWithUnderlying()
    val record = createRecord()
    dc.actor ! DeployCoordinator.StartDeploy(record)

    dc.deployProbe.expectMsgPF() { case DeployGroupRunner.Start =>
    }

    dc.ul.deployRunners.keys should contain(record.uuid)

    dc.deployRunnerRecords should contain(record)
  }

  it should "queue a StartDeploy message if the deploy is already running" in {
    val dc = createDeployCoordinatorWithUnderlying()
    val record = createRecord(projectName = "test", stage = "TEST")
    val recordTwo = createRecord(projectName = "test", stage = "TEST")

    dc.actor ! DeployCoordinator.StartDeploy(record)
    dc.deployProbe.expectMsg(DeployGroupRunner.Start)

    dc.actor ! DeployCoordinator.StartDeploy(recordTwo)
    dc.deployProbe.expectNoMsg()
    dc.ul.deferredDeployQueue.size should be(1)
    dc.ul.deferredDeployQueue.head should be(
      DeployCoordinator.StartDeploy(recordTwo)
    )
  }

  it should "queue a StartDeploy message if there are already too many running" in {
    val dc = createDeployCoordinatorWithUnderlying(2)
    val record = createRecord(projectName = "test", stage = "TEST")
    val recordTwo = createRecord(projectName = "test2", stage = "TEST")
    val recordThree = createRecord(projectName = "test3", stage = "TEST")

    dc.actor ! DeployCoordinator.StartDeploy(record)
    dc.deployProbe.expectMsg(DeployGroupRunner.Start)
    dc.ul.deferredDeployQueue.size should be(0)

    dc.actor ! DeployCoordinator.StartDeploy(recordTwo)
    dc.deployProbe.expectMsg(DeployGroupRunner.Start)
    dc.ul.deferredDeployQueue.size should be(0)

    dc.actor ! DeployCoordinator.StartDeploy(recordThree)
    dc.deployProbe.expectNoMsg()
    dc.ul.deferredDeployQueue.size should be(1)
    dc.ul.deferredDeployQueue.head should be(
      DeployCoordinator.StartDeploy(recordThree)
    )
  }

  it should "dequeue StartDeploy messages when deploys complete" in {
    val dc = createDeployCoordinatorWithUnderlying()
    val record = createRecord(projectName = "test", stage = "TEST")
    val recordTwo = createRecord(projectName = "test", stage = "TEST")

    dc.actor ! DeployCoordinator.StartDeploy(record)
    dc.actor ! DeployCoordinator.StartDeploy(recordTwo)

    dc.deployProbe.expectMsg(DeployGroupRunner.Start)
    dc.ul.deferredDeployQueue.size should be(1)
    dc.ul.deferredDeployQueue.head should be(
      DeployCoordinator.StartDeploy(recordTwo)
    )

    dc.deployProbe.expectNoMsg()
    dc.actor ! DeployCoordinator.CleanupDeploy(record.uuid)

    dc.deployProbe.expectMsg(DeployGroupRunner.Start)
    dc.ul.deferredDeployQueue.size should be(0)
  }

  case class DC(deployProbe: TestProbe, actor: ActorRef)

  def createDeployCoordinator(maxDeploys: Int = 5) = {
    val deployGroupProbe = TestProbe()
    val deployGroupRunnerFactory =
      (_: ActorRefFactory, record: Record, _: ActorRef) => deployGroupProbe.ref
    val stopFlagAgent = Agent(Map.empty[UUID, String])
    val ref = system.actorOf(
      Props(
        classOf[DeployCoordinator],
        deployGroupRunnerFactory,
        maxDeploys,
        stopFlagAgent
      )
    )
    DC(deployGroupProbe, ref)
  }

  case class DCwithUnderlying(
      deployProbe: TestProbe,
      actor: ActorRef,
      ul: DeployCoordinator,
      deployRunnerRecords: mutable.Set[Record]
  )

  def createDeployCoordinatorWithUnderlying(maxDeploys: Int = 5) = {
    val deployProbe = TestProbe()
    val deployRunnerRecords = mutable.Set.empty[Record]
    val deployGroupRunnerFactory =
      (_: ActorRefFactory, record: Record, _: ActorRef) => {
        deployRunnerRecords.add(record)
        deployProbe.ref
      }
    val stopFlagAgent = Agent(Map.empty[UUID, String])
    val ref = TestActorRef(
      new DeployCoordinator(deployGroupRunnerFactory, maxDeploys, stopFlagAgent)
    )
    DCwithUnderlying(deployProbe, ref, ref.underlyingActor, deployRunnerRecords)
  }
}
