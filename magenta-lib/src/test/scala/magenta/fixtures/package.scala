package magenta

import json.{DeployInfoData, DeployInfoJsonInputFile, DeployInfoHost}
import org.joda.time.DateTime

package object fixtures {
  val CODE = Stage("CODE")
  val PROD = Stage("PROD")

  val app1 = App("the_role")

  val deployinfoSingleHost = stubDeployInfo(List(Host("the_host", stage=CODE.name).app(app1)))

  val basePackageType = stubPackageType(Seq("init_action_one"), Seq("action_one"), Set(app1))

  val baseRecipe = Recipe("one",
    actionsBeforeApp = basePackageType.mkAction("init_action_one") :: Nil,
    actionsPerHost = basePackageType.mkAction("action_one") :: Nil,
    dependsOn = Nil)

  def project(recipes: Recipe*) = Project(Map.empty, recipes.map(r => r.name -> r).toMap)

  def stubPackage() = Package("stub project", Set(), Map(), "stub-package-type", null)

  def stubPackageType(perAppActionNames: Seq[String], perHostActionNames: Seq[String],
                      apps: Set[App]) = StubPackageType(
    perAppActions = {
      case name if (perAppActionNames.contains(name)) => (_,_) => List(StubTask(name + " per app task"))
    },
    perHostActions = {
      case name if (perHostActionNames.contains(name))=> host =>
        List(StubTask(name + " per host task on " + host.name, Some(host)))
    },
    pkg = stubPackage().copy(pkgApps = apps)
  )

  def testParams() = DeployParameters(
    Deployer("default deployer"),
    Build("default project", "default version"),
    Stage("test stage")
  )

  def parameters(stage: Stage = PROD, version: String = "version") =
    DeployParameters(Deployer("tester"), Build("project", version), stage)

  def stubDeployInfo(hosts: List[Host] = Nil, data: Map[String, List[Data]] = Map.empty): DeployInfo = {
    val deployHosts = hosts.flatMap{ host => host.apps.map{app =>
      DeployInfoHost(host.name, app.name, host.tags.get("group").getOrElse(""), host.stage, None, None, None, None)
    }}
    val deployData = data.mapValues{ list =>
      list.map(data => DeployInfoData(data.app, data.stage, data.value, data.comment))
    }
    DeployInfo(DeployInfoJsonInputFile(deployHosts,None,deployData), Some(new DateTime()))
  }

}
