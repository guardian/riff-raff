package deployment

import java.util.UUID

import com.amazonaws.services.s3.AmazonS3Client
import deployment.TaskRunner.PrepareDeploy
import magenta.{Build, DeployContext, DeployParameters, DeployReporter, Deployer, Host, KeyRing, NamedStack, Project, Stage}
import magenta.tasks._
import org.scalatest.mock.MockitoSugar

object Fixtures extends MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  implicit val artifactClient = mock[AmazonS3Client]

  val threeSimpleTasks = List(
    S3Upload("test-bucket", Seq()),
    SayHello(Host("testHost")),
    HealthcheckGrace(1000)
  )

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

  def createContext(tasks: List[Task], prepareDeploy: PrepareDeploy): DeployContext =
    createContext(tasks, prepareDeploy.record, prepareDeploy.reporter)
  def createContext(tasks: List[Task], record: Record, reporter: DeployReporter): DeployContext = {
    val taskGraph = TaskGraph.fromTaskList(tasks, record.parameters.stacks.head.name)
    DeployContext(record.uuid, record.parameters, Project(), taskGraph, reporter)
  }

  def createReporter(record: Record) = DeployReporter.rootReporterFor(record.uuid, record.parameters)
}
