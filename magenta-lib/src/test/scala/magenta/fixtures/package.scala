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

  def stubPackage = DeploymentPackage("stub project", Set(app1), Map(), "stub-package-type", null)

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

  def stubLookup(hosts: Seq[Host] = Nil, data: Map[String, Seq[Datum]] = Map.empty): Lookup = {
    val deployHosts = hosts.flatMap{ host => host.apps.map{app =>
      DeployInfoHost(host.name, app.name, host.tags.get("group").getOrElse(""), host.stage, None, None, None, None)
    }}
    val deployData = data.mapValues{ list =>
      list.map(data => DeployInfoData(data.app, data.stage, data.value, data.comment))
    }
    DeployInfoLookupShim(
      DeployInfo(DeployInfoJsonInputFile(deployHosts.toList,None,deployData.mapValues(_.toList)), Some(new DateTime())),
      new SecretProvider {
        def lookup(service: String, account: String): Option[String] = None
      }
    )
  }

}
