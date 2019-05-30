package deployment

import java.util.UUID

import magenta.graph.{DeploymentGraph, DeploymentTasks, Graph}
import magenta.tasks._
import magenta.{App, Build, DeployContext, DeployParameters, DeployReporter, Deployer, Host, KeyRing, Region, Stage}
import org.joda.time.DateTime
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.services.s3.S3Client

object Fixtures extends MockitoSugar {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  implicit val artifactClient: S3Client = mock[S3Client]

  private val host = Host("testHost", App("testApp"), "CODE", "testStack")

  val threeSimpleTasks: List[Task] = List(
    S3Upload(Region("eu-west-1"), "test-bucket", Seq()),
    SayHello(host),
    ChangeSwitch(host, "http", 8080, "switchPath", "bobbinSwitch", desiredState = true)
  )

  val twoTasks = List(
    S3Upload(Region("eu-west-1"), "test-bucket", Seq()),
    ChangeSwitch(host, "http", 8080, "switchPath", "bobbinSwitch", desiredState = true)
  )

  val threeSimpleTasksGraph = DeploymentGraph(threeSimpleTasks, "test")

  val simpleGraph: Graph[DeploymentTasks] = {
    DeploymentGraph(twoTasks, "branch one") joinParallel DeploymentGraph(twoTasks, "branch two")
  }

  val dependentGraph: Graph[DeploymentTasks] = {
    (DeploymentGraph(twoTasks, "one") joinSeries DeploymentGraph(twoTasks, "two")) joinParallel DeploymentGraph(twoTasks, "branch two")
  }

  def createRecord(
    projectName: String = "test",
    stage: String = "TEST",
    buildId: String = "1",
    deployer: String = "Tester",
    stacks: Seq[String] = Seq("test"),
    uuid:UUID = UUID.randomUUID()
  ) = DeployRecord(
    DateTime.now(),
    uuid,
    DeployParameters(Deployer(deployer),
      Build(projectName, buildId),
      Stage(stage)
    )
  )

  def createContext(taskGraph: Graph[DeploymentTasks], uuid: UUID, parameters: DeployParameters): DeployContext =
    DeployContext(uuid, parameters, taskGraph)

  def createReporter(record: Record) = DeployReporter.rootReporterFor(record.uuid, record.parameters)
}
