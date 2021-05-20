package magenta

import enumeratum.{Enum, EnumEntry, PlayJsonEnum}

import java.util.UUID
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.regions.{Region => AWSRegion}
import magenta.input._
import magenta.tasks.Task
import software.amazon.awssdk.services.sts.StsClient

import scala.collection.immutable
import scala.math.Ordering.OptionOrdering

case class Host(name: String,
                app: App,
                stage: String = "NO_STAGE",
                stack: String,
                connectAs: Option[String] = None,
                tags: Map[String, String] = Map.empty) {

  def isValidForStack(s: Stack) = s.name == stack
}

case class Datum(
  stack: String,
  app: String,
  stage: String,
  value: String,
  comment: Option[String]
)

case class HostList(hosts: Seq[Host]) {
  def dump = hosts
    .sortBy { _.name }
    .map { h => s" ${h.name}: ${h.app}" }
    .mkString("\n")

  def filterByStage(stage: Stage): HostList = new HostList(hosts.filter(_.stage == stage.name))

  def byStackAndApp = {
    implicit val appOrder: Ordering[App] = Ordering.by(_.name)
    implicit val hostOrder: Ordering[Host] = Ordering.by(_.name)
    implicit def someBeforeNone[T](implicit ord: Ordering[T]): Ordering[Option[T]] =
      new OptionOrdering[T] { val optionOrdering = ord.reverse }.reverse
    implicit def setOrder[T](implicit ord: Ordering[T]): Ordering[Set[T]] = Ordering.by(_.toIterable)
    implicit def seqOrder[T](implicit ord: Ordering[T]): Ordering[Seq[T]] = Ordering.by(_.toIterable)

    hosts.groupBy(h => (h.stack, h.app)).toSeq.sorted
  }
}
object HostList {
  implicit def listOfHostsAsHostList(hosts: Seq[Host]): HostList = new HostList(hosts)
  implicit def hostListAsListOfHosts(hostList: HostList): Seq[Host] = hostList.hosts
}

case class DeploymentResources(reporter: DeployReporter, lookup: Lookup, artifactClient: S3Client, stsClient: StsClient) {
  def assembleKeyring(target: DeployTarget, pkg: DeploymentPackage): KeyRing = {
    val keyring: KeyRing = lookup.keyRing(target.parameters.stage, pkg.app, target.stack)
    reporter.verbose(s"Keyring for ${pkg.name} in ${target.stack.name}/${target.region.name}: $keyring")
    keyring
  }
}

case class StsDeploymentResources(deployId: UUID, stsClient: StsClient)
object StsDeploymentResources {
  def fromDeploymentResources(resources: DeploymentResources): StsDeploymentResources =
    StsDeploymentResources(resources.reporter.messageContext.deployId, resources.stsClient)
}


case class DeployTarget(parameters: DeployParameters, stack: Stack, region: Region)

trait DeploymentStep {
  def app: App
  def description: String
  def resolve(resources: DeploymentResources, target: DeployTarget): List[Task]
}

case class App (name: String)

case class Stage(name: String)

case class Build(projectName:String, id:String)

case class Stack(name: String) extends AnyVal

case class Region(name: String) extends AnyVal {
  def awsRegion: AWSRegion = AWSRegion.of(name)
}

case class Deployer(name: String)

sealed trait Strategy extends EnumEntry {
  def description: String
  def userDescription: String
}
object Strategy extends Enum[Strategy] with PlayJsonEnum[Strategy] {
  val values: immutable.IndexedSeq[Strategy] = findValues
  case object MostlyHarmless extends Strategy {
    val description = "Avoids destructive deployment operations"
    val userDescription = s"$entryName (default): $description"
  }
  case object Dangerous extends Strategy {
    val description = "Allows all deployment operations even if destructive"
    val userDescription = s"$entryName (USE WITH CARE 💣): $description"
  }
}

case class DeployParameters(deployer: Deployer,
                            build: Build,
                            stage: Stage,
                            selector: DeploymentSelector = All,
                            updateStrategy: Strategy)
