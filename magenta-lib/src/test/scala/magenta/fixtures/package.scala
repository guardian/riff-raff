package magenta

import magenta.deployment_type._
import org.joda.time.DateTime

package object fixtures {
  val CODE = Stage("CODE")
  val PROD = Stage("PROD")

  val app1 = App("the_role")

  val lookupEmpty = stubLookup()

  val lookupSingleHost = stubLookup(List(Host("the_host", stage = CODE.name).app(app1)))

  val basePackageType = stubDeploymentType(Seq("init_action_one"))

  val baseRecipe = Recipe(
    "one",
    deploymentSteps = basePackageType.mkDeploymentStep("init_action_one")(stubPackage(basePackageType)) :: Nil,
    dependsOn = Nil)

  def project(recipes: Recipe*) = Project(Map.empty, recipes.map(r => r.name -> r).toMap)

  def project(recipe: Recipe, stacks: Stack*) = Project(Map.empty, Map(recipe.name -> recipe), defaultStacks = stacks)

  def stubPackage(deploymentType: DeploymentType) =
    DeploymentPackage("stub project", Seq(app1), Map(), "stub-package-type", null, false, Seq(deploymentType))

  def stubDeploymentType(actionNames: Seq[String],
                         params: ParamRegister => List[Param[_]] = _ => Nil,
                         name: String = "stub-package-type") = {
    StubDeploymentType(
      actionsMap = actionNames.map { name =>
        name -> Action(name) { (_, _, target) =>
          List(
            StubTask(name + " per app task number one", target.region),
            StubTask(name + " per app task number two", target.region)
          )
        }(StubActionRegister)
      }.toMap,
      actionNames.toList,
      params,
      name
    )
  }

  def testParams() = DeployParameters(
    Deployer("default deployer"),
    Build("default project", "default version"),
    Stage("test stage")
  )

  def parameters(stage: Stage = PROD, version: String = "version") =
    DeployParameters(Deployer("tester"), Build("project", version), stage)

  def stubLookup(hostsSeq: Seq[Host] = Nil, resourceData: Map[String, Seq[Datum]] = Map.empty): Lookup = {
    new Lookup {
      def stages: Seq[String] = hostsSeq.map(_.stage).distinct
      def lastUpdated: DateTime = new DateTime()
      def data: DataLookup = new DataLookup {
        def datum(key: String, app: App, stage: Stage, stack: Stack): Option[Datum] = {
          val matchingList = resourceData.getOrElse(key, List.empty)
          stack match {
            case UnnamedStack =>
              matchingList.filter(_.stack.isEmpty).find { data =>
                data.appRegex.findFirstMatchIn(app.name).isDefined &&
                data.stageRegex.findFirstMatchIn(stage.name).isDefined
              }
            case NamedStack(stackName) =>
              matchingList.filter(_.stack.isDefined).find { data =>
                data.stackRegex.exists(_.findFirstMatchIn(stackName).isDefined) &&
                data.appRegex.findFirstMatchIn(app.name).isDefined &&
                data.stageRegex.findFirstMatchIn(stage.name).isDefined
              }
          }
        }
        def all: Map[String, Seq[Datum]] = resourceData
        def keys: Seq[String] = resourceData.keys.toSeq
      }

      def credentials(stage: Stage, apps: Set[App]): Map[String, ApiCredentials] = ???

      def name: String = "stub"

      def hosts: HostLookup = new HostLookup {
        def get(pkg: DeploymentPackage, app: App, params: DeployParameters, stack: Stack): Seq[Host] = {
          hostsSeq.filter { host =>
            host.stage == params.stage.name &&
            host.apps.contains(app) &&
            host.isValidForStack(stack)
          }
        }
        def all: Seq[Host] = hostsSeq
      }

      def keyRing(stage: Stage, apps: Set[App], stack: Stack) = KeyRing()

      def getLatestAmi(region: String)(tags: Map[String, String]): Option[String] = ???
    }
  }

}
