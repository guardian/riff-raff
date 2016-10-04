package deployment

import java.util.UUID

import com.amazonaws.services.s3.AmazonS3Client
import magenta.graph.{DeploymentGraph, DeploymentTasks, Graph}
import magenta.{Build, DeployContext, DeployParameters, DeployReporter, Deployer, Host, KeyRing, NamedStack, Project, Region, Stage}
import magenta.tasks._
import org.scalatest.mock.MockitoSugar

object Fixtures extends MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  implicit val artifactClient = mock[AmazonS3Client]

  val threeSimpleTasks: List[Task] = List(
    S3Upload(Region("eu-west-1"), "test-bucket", Seq()),
    SayHello(Host("testHost")),
    HealthcheckGrace(1000)
  )

  val twoTasks = List(
    S3Upload(Region("eu-west-1"), "test-bucket", Seq()),
    HealthcheckGrace(1000)
  )

  val simpleGraph: Graph[DeploymentTasks] = {
    DeploymentGraph(twoTasks, "branch one") joinParallel DeploymentGraph(twoTasks, "branch two")
  }

  def createRecord(
    projectName: String = "test",
    stage: String = "TEST",
    buildId: String = "1",
    deployer: String = "Tester",
    stacks: Seq[String] = Seq("test"),
    uuid:UUID = UUID.randomUUID()
  ) = DeployRecord(uuid,
    DeployParameters(Deployer(deployer),
      Build(projectName, buildId),
      Stage(stage),
      stacks = stacks.map(NamedStack.apply)
    )
  )

  def createContext(tasks: List[Task], uuid: UUID, parameters: DeployParameters): DeployContext =
    createContext(DeploymentGraph(tasks, parameters.stacks.head.name), uuid, parameters)
  def createContext(taskGraph: Graph[DeploymentTasks], uuid: UUID, parameters: DeployParameters): DeployContext =
    DeployContext(uuid, parameters, taskGraph)

  def createReporter(record: Record) = DeployReporter.rootReporterFor(record.uuid, record.parameters)
}
