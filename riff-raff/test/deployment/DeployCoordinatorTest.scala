package deployment

import java.util.UUID

import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import com.amazonaws.services.s3.AmazonS3Client
import com.typesafe.config.ConfigFactory
import deployment.DeployCoordinator.{CheckStopFlag, StartDeploy, StopDeploy}
import deployment.TaskRunner._
import magenta.tasks.{HealthcheckGrace, S3Upload, SayHello, SuspendAlarmNotifications}
import magenta.{Build, DeployContext, DeployParameters, Deployer, DeploymentPackage, Host, KeyRing, Project, Stage}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Success

object DeployCoordinatorTest {
  lazy val testConfig = ConfigFactory.parseMap(
    Map("akka.test.single-expect-default" -> "500").asJava
  ).withFallback(ConfigFactory.load())
}

class DeployCoordinatorTest extends TestKit(ActorSystem("DeployCoordinatorTest", DeployCoordinatorTest.testConfig))
  with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  implicit val fakeKeyRing = KeyRing()
  implicit val artifactClient = mock[AmazonS3Client]

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "DeployCoordinator" should "respond to StartDeploy with PrepareDeploy to runner" in {
    val dc = createDeployCoordinatorWithUnderlying
    val record = DeployRecord(
      UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622"),
      DeployParameters(Deployer("Tester"), Build("test", "1"), Stage("TEST"))
    )
    dc.actor ! StartDeploy(record)

    dc.probe.expectMsgPF(){
      case PrepareDeploy(pdRecord, reporter) =>
        pdRecord should be(record)
        reporter.messageContext.deployId should be(record.uuid)
        reporter.messageContext.parameters should be(record.parameters)
    }

    dc.ul.deployStateMap.keys should contain(record.uuid)
  }

  it should "queue a StartDeploy message if the deploy is already running" in {
    val dc = createDeployCoordinatorWithUnderlying
    val record = DeployRecord(
      UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622"),
      DeployParameters(Deployer("Tester"), Build("test", "1"), Stage("TEST"))
    )
    val recordTwo = DeployRecord(
      UUID.fromString("60efc45c-8441-4abb-81cf-e7d84e3469c6"),
      DeployParameters(Deployer("TesterTwo"), Build("test", "6"), Stage("TEST"))
    )

    dc.actor ! StartDeploy(record)
    dc.probe.expectMsgClass(classOf[PrepareDeploy])

    dc.actor ! StartDeploy(recordTwo)
    dc.probe.expectNoMsg()
    dc.ul.deferredDeployQueue.size should be(1)
    dc.ul.deferredDeployQueue.head should be(StartDeploy(recordTwo))
  }

  it should "respond to a DeployReady message with the appropriate first task" in {
    val dc = createDeployCoordinator
    val record = DeployRecord(
      UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622"),
      DeployParameters(Deployer("Tester"), Build("test", "1"), Stage("TEST"))
    )

    dc.actor ! StartDeploy(record)
    val prepareDeploy = dc.probe.expectMsgClass(classOf[PrepareDeploy])
    val context = DeployContext(record.uuid, record.parameters, Project(), List(S3Upload("test-bucket", Seq())), prepareDeploy.reporter)
    dc.probe.reply(DeployReady(record, context))
    dc.probe.expectMsgPF(){
      case RunTask(r, t, _, _) =>
        t.task should be(S3Upload("test-bucket", Seq()))
        r should be(record)
    }
  }

  it should "respond to a final TaskCompleted message with cleanup" in {
    val dc = createDeployCoordinatorWithUnderlying
    val record = DeployRecord(
      UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622"),
      DeployParameters(Deployer("Tester"), Build("test", "1"), Stage("TEST"))
    )

    dc.actor ! StartDeploy(record)
    val prepareDeploy = dc.probe.expectMsgClass(classOf[PrepareDeploy])
    val context = DeployContext(record.uuid, record.parameters, Project(), List(S3Upload("test-bucket", Seq())), prepareDeploy.reporter)
    dc.probe.reply(DeployReady(record, context))
    val runTask = dc.probe.expectMsgClass(classOf[RunTask])
    dc.probe.reply(TaskCompleted(record, runTask.task))
    dc.probe.expectNoMsg()

    dc.ul.deployStateMap.keys shouldNot contain(record.uuid)
  }

  it should "dequeue StartDeploy messages when deploys complete" in {
    val dc = createDeployCoordinator
    val record = DeployRecord(
      UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622"),
      DeployParameters(Deployer("Tester"), Build("test", "1"), Stage("TEST"))
    )
    val recordTwo = DeployRecord(
      UUID.fromString("60efc45c-8441-4abb-81cf-e7d84e3469c6"),
      DeployParameters(Deployer("TesterTwo"), Build("test", "6"), Stage("TEST"))
    )

    dc.actor ! StartDeploy(record)
    dc.actor ! StartDeploy(recordTwo)
    val prepareDeploy = dc.probe.expectMsgClass(classOf[PrepareDeploy])
    val context = DeployContext(record.uuid, record.parameters, Project(), List(S3Upload("test-bucket", Seq())), prepareDeploy.reporter)
    dc.probe.reply(DeployReady(record, context))
    val runTask = dc.probe.expectMsgClass(classOf[RunTask])
    dc.probe.reply(TaskCompleted(record, runTask.task))

    val prepareDeployTwo = dc.probe.expectMsgClass(classOf[PrepareDeploy])
    prepareDeployTwo.record should be(recordTwo)
  }

  it should "process a list of tasks" in {
    val dc = createDeployCoordinator
    val record = DeployRecord(
      UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622"),
      DeployParameters(Deployer("Tester"), Build("test", "1"), Stage("TEST"))
    )

    dc.actor ! StartDeploy(record)
    val prepareDeploy = dc.probe.expectMsgClass(classOf[PrepareDeploy])
    val tasks = List(
      S3Upload("test-bucket", Seq()),
      SayHello(Host("testHost")),
      HealthcheckGrace(1000)
    )
    val context = DeployContext(record.uuid, record.parameters, Project(), tasks, prepareDeploy.reporter)

    dc.probe.reply(DeployReady(record, context))
    val runS3Upload = dc.probe.expectMsgClass(classOf[RunTask])
    runS3Upload.task.task should be(S3Upload("test-bucket", Seq()))

    dc.probe.reply(TaskCompleted(runS3Upload.record, runS3Upload.task))
    val runSayHello = dc.probe.expectMsgClass(classOf[RunTask])
    runSayHello.task.task should be(SayHello(Host("testHost")))

    dc.probe.reply(TaskCompleted(runSayHello.record, runSayHello.task))
    val runGrace = dc.probe.expectMsgClass(classOf[RunTask])
    runGrace.task.task should be(HealthcheckGrace(1000))

    dc.probe.reply(TaskCompleted(runGrace.record, runGrace.task))
    dc.probe.expectNoMsg()
  }

  it should "process a task failure" in {
    val dc = createDeployCoordinatorWithUnderlying
    val record = DeployRecord(
      UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622"),
      DeployParameters(Deployer("Tester"), Build("test", "1"), Stage("TEST"))
    )

    dc.actor ! StartDeploy(record)
    val prepareDeploy = dc.probe.expectMsgClass(classOf[PrepareDeploy])
    val tasks = List(
      S3Upload("test-bucket", Seq()),
      SayHello(Host("testHost")),
      HealthcheckGrace(1000)
    )
    val context = DeployContext(record.uuid, record.parameters, Project(), tasks, prepareDeploy.reporter)

    dc.probe.reply(DeployReady(record, context))
    val runS3Upload = dc.probe.expectMsgClass(classOf[RunTask])
    runS3Upload.task.task should be(S3Upload("test-bucket", Seq()))

    dc.probe.reply(TaskFailed(runS3Upload.record, new RuntimeException("Something bad happened")))
    dc.probe.expectNoMsg()
    dc.ul.deployStateMap.keySet shouldNot contain(record.uuid)
  }

  it should "set the stop flag" in {
    val dc = createDeployCoordinatorWithUnderlying
    dc.actor ! StopDeploy(UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622"), "testUser")
    dc.probe.expectNoMsg()
    dc.ul.stopFlagMap should contain(UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622") -> Some("testUser"))
  }

  it should "correctly report the stop flag status" in {
    import akka.pattern.ask
    implicit val timeout = Timeout(1 second)

    val dc = createDeployCoordinator
    val stopFlagResult = dc.actor ? CheckStopFlag(UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622")) mapTo manifest[Boolean]
    Await.result(stopFlagResult, timeout.duration) should be(false)

    dc.actor ! StopDeploy(UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622"), "testUser")
    expectNoMsg()

    val stopFlagResult2 = dc.actor ? CheckStopFlag(UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622")) mapTo manifest[Boolean]
    Await.result(stopFlagResult2, timeout.duration) should be(true)
  }

  case class DC(probe: TestProbe, actor: ActorRef)

  def createDeployCoordinator = {
    val runnerProbe = TestProbe()
    val taskRunnerFactory = (_: ActorRefFactory) => runnerProbe.ref
    val ref = system.actorOf(Props(classOf[DeployCoordinator], taskRunnerFactory))
    DC(runnerProbe, ref)
  }

  case class DCwithUnderlying(probe: TestProbe, actor: ActorRef, ul: DeployCoordinator)

  def createDeployCoordinatorWithUnderlying = {
    val runnerProbe = TestProbe()
    val taskRunnerFactory = (_: ActorRefFactory) => runnerProbe.ref
    val ref = TestActorRef(new DeployCoordinator(taskRunnerFactory))
    DCwithUnderlying(runnerProbe, ref, ref.underlyingActor)
  }
}
