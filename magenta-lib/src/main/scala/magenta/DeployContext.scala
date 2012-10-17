package magenta

import tasks.Task

object DeployContext {
  def apply(parameters: DeployParameters, project: Project, allHosts: HostList): DeployContext = {
    val stageHosts = {
      val stageHosts = allHosts.filterByStage(parameters.stage)
      MessageBroker.verbose("All possible hosts in stage:\n" + stageHosts.dump)
      stageHosts
    }
    val tasks = {
      MessageBroker.info("Resolving tasks...")
      val taskList = Resolver.resolve(project, parameters.recipe.name, stageHosts, parameters.stage)
      MessageBroker.taskList(taskList)
      taskList
    }
    DeployContext(parameters, project, stageHosts, tasks)
  }
}

case class DeployContext(parameters: DeployParameters, project: Project, stageHosts: HostList, tasks: List[Task]) {
  val deployer = parameters.deployer
  val buildName = parameters.build.projectName
  val buildId = parameters.build.id
  val recipe = parameters.recipe.name
  val stage = parameters.stage

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
