package magenta


case class DeployContext(parameters: DeployParameters, project: Project, hosts: HostList) {
  val deployer = parameters.deployer
  val buildName = parameters.build.name
  val buildId = parameters.build.id
  val recipe = parameters.recipe.name
  val stage = parameters.stage

  lazy val stageHosts = {
    val stageHosts = hosts.filterByStage(parameters.stage)
    MessageBroker.verbose("All possible hosts in stage:\n" + stageHosts.dump)
    stageHosts
  }

  lazy val hostNames = tasks.flatMap(_.taskHosts).map(_.name).distinct

  lazy val tasks = {
    MessageBroker.info("Resolving tasks...")
    val taskList = Resolver.resolve(project, parameters.recipe.name, stageHosts, parameters.stage)
    MessageBroker.taskList(taskList)
    taskList
  }

  def execute(keyRing: KeyRing) {
    MessageBroker.deployContext(parameters) {
      if (tasks.isEmpty) MessageBroker.fail("No tasks were found to execute. Ensure the app(s) are in the list supported by this stage/host.")

      tasks.foreach { task =>
        MessageBroker.taskContext(task) {
          task.execute(keyRing)
        }
      }
    }
  }

}
