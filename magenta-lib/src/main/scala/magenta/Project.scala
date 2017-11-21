package magenta

import com.amazonaws.services.s3.AmazonS3
import magenta.input._
import magenta.tasks.Task

import scala.math.Ordering.OptionOrdering

case class Host(
    name: String,
    apps: Set[App] = Set.empty,
    stage: String = "NO_STAGE",
    stack: Option[String] = None,
    connectAs: Option[String] = None,
    tags: Map[String, String] = Map.empty)
{
  def app(app: App) = this.copy(apps = apps + app)

  def as(user: String) = this.copy(connectAs = Some(user))

  // this allows "resin" @: Host("some-host")
  def @:(user: String) = as(user)

  lazy val connectStr = (connectAs map { _ + "@" } getOrElse "") + name

  def isValidForStack(s: Stack) = s match {
    case NamedStack(stackName) => stack.contains(stackName)
    case UnnamedStack => true
  }
}

case class Datum(
  stack: Option[String],
  app: String,
  stage: String,
  value: String,
  comment: Option[String]
) {
  lazy val stackRegex = stack.map(s => s"^$s$$".r)
  lazy val appRegex = ("^%s$" format app).r
  lazy val stageRegex = ("^%s$" format stage).r
}

case class HostList(hosts: Seq[Host]) {
  def dump = hosts
    .sortBy { _.name }
    .map { h => s" ${h.name}: ${h.apps.map(_.toString).mkString(", ")}" }
    .mkString("\n")

  def filterByStage(stage: Stage): HostList = new HostList(hosts.filter(_.stage == stage.name))

  def byStackAndApp = {
    implicit val appOrder: Ordering[App] = Ordering.by(_.name)
    implicit val hostOrder: Ordering[Host] = Ordering.by(_.name)
    implicit def someBeforeNone[T](implicit ord: Ordering[T]): Ordering[Option[T]] =
      new OptionOrdering[T] { val optionOrdering = ord.reverse }.reverse
    implicit def setOrder[T](implicit ord: Ordering[T]): Ordering[Set[T]] = Ordering.by(_.toIterable)
    implicit def seqOrder[T](implicit ord: Ordering[T]): Ordering[Seq[T]] = Ordering.by(_.toIterable)

    hosts.groupBy(h => (h.stack, h.apps)).toSeq.sorted
  }
}
object HostList {
  implicit def listOfHostsAsHostList(hosts: Seq[Host]): HostList = new HostList(hosts)
  implicit def hostListAsListOfHosts(hostList: HostList): Seq[Host] = hostList.hosts
}

case class DeploymentResources(reporter: DeployReporter, lookup: Lookup, artifactClient: AmazonS3) {
  def assembleKeyring(target: DeployTarget, pkg: DeploymentPackage): KeyRing = {
    val keyring = lookup.keyRing(target.parameters.stage, pkg.apps.toSet, target.stack)
    reporter.verbose(s"Keyring for ${pkg.name} in ${target.stack.nameOption.getOrElse("")}/${target.region.name}: $keyring")
    keyring
  }
}

case class DeployTarget(parameters: DeployParameters, stack: Stack, region: Region)

trait DeploymentStep {
  def apps: Seq[App]
  def description: String
  def resolve(resources: DeploymentResources, target: DeployTarget): List[Task]
}

case class App (name: String)

case class Recipe(
  name: String,
  deploymentSteps: Iterable[DeploymentStep] = Nil,
  dependsOn: List[String] = Nil
)

case class Project(
  packages: Map[String, DeploymentPackage] = Map.empty,
  recipes: Map[String, Recipe] = Map.empty,
  defaultStacks: Seq[Stack] = Seq()
) {
  lazy val applications = packages.values.flatMap(_.apps).toSet
}

case class Stage(name: String)
case class Build(projectName:String, id:String)
case class RecipeName(name:String)
object DefaultRecipe {
  def apply() = RecipeName("default")
}

sealed trait Stack {
  def nameOption: Option[String]
}
case class NamedStack(name: String) extends Stack {
  override def nameOption = Some(name)
}
case object UnnamedStack extends Stack {
  override def nameOption = None
}

case class Region(name: String) extends AnyVal

case class Deployer(name: String)

case class DeployParameters(
                             deployer: Deployer,
                             build: Build,
                             stage: Stage,
                             recipe: RecipeName = DefaultRecipe(),
                             stacks: Seq[NamedStack] = Seq(),
                             hostList: List[String] = Nil,
                             selector: DeploymentSelector = All
                             )
