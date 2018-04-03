package magenta

import com.amazonaws.services.s3.AmazonS3
import magenta.input._
import magenta.tasks.Task

import scala.math.Ordering.OptionOrdering

case class Host(
                 name: String,
                 app: App,
                 stage: String = "NO_STAGE",
                 stack: String,
                 connectAs: Option[String] = None,
                 tags: Map[String, String] = Map.empty)
{
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

case class DeploymentResources(reporter: DeployReporter, lookup: Lookup, artifactClient: AmazonS3) {
  def assembleKeyring(target: DeployTarget, pkg: DeploymentPackage): KeyRing = {
    val keyring = lookup.keyRing(target.parameters.stage, pkg.app, target.stack)
    reporter.verbose(s"Keyring for ${pkg.name} in ${target.stack.name}/${target.region.name}: $keyring")
    keyring
  }
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

case class Region(name: String) extends AnyVal

case class Deployer(name: String)

case class DeployParameters(
                             deployer: Deployer,
                             build: Build,
                             stage: Stage,
                             selector: DeploymentSelector = All
                             )
