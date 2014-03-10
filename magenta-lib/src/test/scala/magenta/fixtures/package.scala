package magenta

import json.{DeployInfoData, DeployInfoJsonInputFile, DeployInfoHost}
import org.joda.time.DateTime

package object fixtures {
  val CODE = Stage("CODE")
  val PROD = Stage("PROD")

  val app1 = LegacyApp("the_role")

  val lookupEmpty = stubLookup()

  val lookupSingleHost = stubLookup(List(Host("the_host", stage=CODE.name).app(app1)))

  val basePackageType = stubPackageType(Seq("init_action_one"), Seq("action_one"))

  val baseRecipe = Recipe("one",
    actionsBeforeApp = basePackageType.mkAction("init_action_one")(stubPackage) :: Nil,
    actionsPerHost = basePackageType.mkAction("action_one")(stubPackage) :: Nil,
    dependsOn = Nil)

  def project(recipes: Recipe*) = Project(Map.empty, recipes.map(r => r.name -> r).toMap)

  def stubPackage = DeploymentPackage("stub project", Seq(app1), Map(), "stub-package-type", null)

  def stubPackageType(perAppActionNames: Seq[String], perHostActionNames: Seq[String]) = StubDeploymentType(
    perAppActions = {
      case name if perAppActionNames.contains(name) => pkg => (_,_) => List(StubTask(name + " per app task"))
    },
    perHostActions = {
      case name if perHostActionNames.contains(name) => pkg => host =>
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

  def stubLookup(hosts: Seq[Host] = Nil, resourceData: Map[String, Seq[Datum]] = Map.empty): Lookup = {
    new Lookup {
      def stages: Seq[String] = hosts.map(_.stage).distinct
      def lastUpdated: DateTime = new DateTime()
      def data: Data = new Data {
        def datum(key: String, app: App, stage: Stage): Option[Datum] = {
          val matchingList = resourceData.getOrElse(key, List.empty)
          app match {
            case LegacyApp(name) =>
              matchingList.filter(_.stack.isEmpty).find{data =>
                data.appRegex.findFirstMatchIn(name).isDefined && data.stageRegex.findFirstMatchIn(stage.name).isDefined
              }
            case StackApp(stackName, appName) =>
              matchingList.filter(_.stack.isDefined).find{data =>
                data.stackRegex.exists(_.findFirstMatchIn(appName).isDefined) &&
                  data.appRegex.findFirstMatchIn(appName).isDefined &&
                  data.stageRegex.findFirstMatchIn(stage.name).isDefined
              }
          }
        }
        def all: Map[String, Seq[Datum]] = resourceData
        def keys: Seq[String] = resourceData.keys.toSeq
      }

      def credentials(stage: Stage, apps: Set[App]): Map[String, ApiCredentials] = ???

      def name: String = "stub"

      def instances: Instances = new Instances {
        def get(app: App, stage: Stage): Seq[Host] = {
          hosts.filter{ host => host.stage == stage.name && host.apps.contains(app) }
        }
        def all: Seq[Host] = hosts
      }
    }
  }

}
