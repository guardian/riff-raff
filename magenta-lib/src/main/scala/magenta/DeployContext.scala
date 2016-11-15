package magenta

import java.util.UUID

import magenta.graph.{DeploymentGraph, DeploymentTasks, Graph}

object DeployContext {
  def apply(deployId: UUID, parameters: DeployParameters, project: Project,
    resources: DeploymentResources, region: Region): DeployContext = {

    val tasks = {
      resources.reporter.info("Resolving tasks...")
      Resolver.resolve(project, parameters, resources, region)
    }
    DeployContext(deployId, parameters, tasks)
  }
}

case class DeployContext(uuid: UUID, parameters: DeployParameters, tasks: Graph[DeploymentTasks]) {
  val deployer = parameters.deployer
  val buildName = parameters.build.projectName
  val buildId = parameters.build.id
  val recipe = parameters.recipe.name
  val stage = parameters.stage

  def execute(reporter: DeployReporter) {
    val taskList = DeploymentGraph.toTaskList(tasks)
    if (taskList.isEmpty) reporter.fail("No tasks were found to execute. Ensure the app(s) are in the list supported by this stage/host.")

    taskList.foreach { task =>
      reporter.taskContext(task) { taskLogger =>
        task.execute(taskLogger)
      }
    }
  }
}

case class DeployStoppedException(message:String) extends Exception(message)
