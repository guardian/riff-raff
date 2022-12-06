package magenta

import magenta.Strategy.MostlyHarmless
import magenta.deployment_type._
import org.joda.time.DateTime

package object fixtures {
  val CODE = Stage("CODE")
  val PROD = Stage("PROD")

  val app1 = App("the_role")

  val stack = Stack("test-stack")

  val lookupEmpty = stubLookup()

  val lookupSingleHost = stubLookup(
    List(Host("the_host", app1, stage = CODE.name, stack.name))
  )

  val basePackageType = stubDeploymentType(Seq("init_action_one"))

  def stubPackage(deploymentType: DeploymentType): DeploymentPackage =
    DeploymentPackage(
      "stub project",
      app1,
      Map(),
      "stub-package-type",
      null,
      Seq(deploymentType)
    )

  def stubDeploymentType(
      actionNames: Seq[String],
      params: ParamRegister => List[Param[_]] = _ => Nil,
      name: String = "stub-package-type"
  ) = {
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
    Stage("test stage"),
    updateStrategy = MostlyHarmless
  )

  def parameters(
      stage: Stage = PROD,
      version: String = "version",
      updateStrategy: Strategy = MostlyHarmless
  ) =
    DeployParameters(
      Deployer("tester"),
      Build("project", version),
      stage,
      updateStrategy = updateStrategy
    )

  def stubLookup(
      hostsSeq: Seq[Host] = Nil,
      resourceData: Map[String, Seq[Datum]] = Map.empty
  ): Lookup = {
    new Lookup {
      def stages: Seq[String] = hostsSeq.map(_.stage).distinct
      def lastUpdated: DateTime = new DateTime()
      def data: DataLookup = new DataLookup {
        def datum(
            key: String,
            app: App,
            stage: Stage,
            stack: Stack
        ): Option[Datum] = {
          val matchingList = resourceData.getOrElse(key, List.empty)
          matchingList.find { data =>
            data.stack == stack.name &&
            data.app == app.name &&
            data.stage == stage.name
          }
        }
        def all: Map[String, Seq[Datum]] = resourceData
        def keys: Seq[String] = resourceData.keys.toSeq
      }

      def credentials(
          stage: Stage,
          apps: Set[App]
      ): Map[String, ApiCredentials] = ???

      def name: String = "stub"

      def hosts: HostLookup = new HostLookup {
        def get(
            pkg: DeploymentPackage,
            app: App,
            params: DeployParameters,
            stack: Stack
        ): Seq[Host] = {
          hostsSeq.filter { host =>
            host.stage == params.stage.name &&
            host.app == app &&
            host.isValidForStack(stack)
          }
        }
        def all: Seq[Host] = hostsSeq
      }

      override def keyRing(stage: Stage, app: App, stack: Stack) = KeyRing()

      def getLatestAmi(
          accountNumber: Option[String],
          tagFilter: Map[String, String] => Boolean
      )(region: String)(tags: Map[String, String]): Option[String] = ???
    }
  }

}
