package magenta

import json.{DeployInfoData, DeployInfoJsonInputFile, DeployInfoHost}
import org.joda.time.DateTime

package object fixtures {
  val CODE = Stage("CODE")
  val PROD = Stage("PROD")

  val app1 = App("the_role")

  val lookupEmpty = stubLookup()

  val lookupSingleHost = stubLookup(List(Host("the_host", stage=CODE.name).app(app1)))

  val basePackageType = stubPackageType(Seq("init_action_one"), Seq("action_one"))

  val baseRecipe = Recipe("one",
    actionsBeforeApp = basePackageType.mkAction("init_action_one")(stubPackage) :: Nil,
    actionsPerHost = basePackageType.mkAction("action_one")(stubPackage) :: Nil,
    dependsOn = Nil)

  def project(recipes: Recipe*) = Project(Map.empty, recipes.map(r => r.name -> r).toMap)

  def project(recipe: Recipe, stacks: Stack*) = Project(Map.empty, Map(recipe.name -> recipe), defaultStacks = stacks)

  def stubPackage = DeploymentPackage("stub project", Seq(app1), Map(), "stub-package-type", null)

  def stubPackageType(perAppActionNames: Seq[String], perHostActionNames: Seq[String]) = StubDeploymentType(
    perAppActions = {
      case name if perAppActionNames.contains(name) => pkg => (_,_,_, _) => List(StubTask(name + " per app task"))
    },
    perHostActions = {
      case name if perHostActionNames.contains(name) => pkg => (_, host, _) =>
        List(StubTask(name + " per host task on " + host.name, Some(host)))
    }
  )

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
              matchingList.filter(_.stack.isEmpty).find{data =>
                data.appRegex.findFirstMatchIn(app.name).isDefined &&
                data.stageRegex.findFirstMatchIn(stage.name).isDefined
              }
            case NamedStack(stackName) =>
              matchingList.filter(_.stack.isDefined).find{data =>
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
          hostsSeq.filter{ host =>
            host.stage == params.stage.name &&
            host.apps.contains(app) &&
            host.isValidForStack(stack)
          }
        }
        def all: Seq[Host] = hostsSeq
      }

      def keyRing(stage: Stage, apps: Set[App], stack: Stack) = KeyRing(SystemUser(None))

      def getLatestAmi(region: String)(tags: Map[String, String]): Option[String] = ???
    }
  }

}
