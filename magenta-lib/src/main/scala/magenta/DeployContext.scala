package magenta

import tasks.Task
import java.util.UUID

object DeployContext {
  def apply(deployId: UUID, parameters: DeployParameters, project: Project,
    resourceLookup: Lookup, rootLogger: DeployLogger): DeployContext = {

    val tasks = {
      rootLogger.info("Resolving tasks...")
      val taskList = Resolver.resolve(project, resourceLookup, parameters, rootLogger)
      rootLogger.taskList(taskList)
      taskList
    }
    DeployContext(deployId, parameters, project, tasks, rootLogger)
  }
}

case class DeployContext(uuid: UUID, parameters: DeployParameters, project: Project,
  tasks: List[Task], rootLogger: DeployLogger) {
  val deployer = parameters.deployer
  val buildName = parameters.build.projectName
  val buildId = parameters.build.id
  val recipe = parameters.recipe.name
  val stage = parameters.stage

  def execute() {
    if (tasks.isEmpty) rootLogger.fail("No tasks were found to execute. Ensure the app(s) are in the list supported by this stage/host.")

    tasks.foreach { task =>
      rootLogger.taskContext(task) { taskLogger =>
        task.execute(taskLogger)
      }
    }
  }
}

case class DeployStoppedException(message:String) extends Exception(message)
