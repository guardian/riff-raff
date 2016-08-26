package magenta

import tasks._
import java.util.UUID

import com.amazonaws.services.s3.AmazonS3

object DeployContext {
  def apply(deployId: UUID, parameters: DeployParameters, project: Project,
    resourceLookup: Lookup, rootReporter: DeployReporter, artifactClient: AmazonS3): DeployContext = {

    val tasks = {
      rootReporter.info("Resolving tasks...")
      val tasks = Resolver.resolve(project, resourceLookup, parameters, rootReporter, artifactClient)
      rootReporter.taskList(tasks.toTaskList)
      tasks
    }
    DeployContext(deployId, parameters, project, tasks, rootReporter)
  }
}

case class DeployContext(uuid: UUID, parameters: DeployParameters, project: Project,
  tasks: TaskGraph, reporter: DeployReporter) {
  val deployer = parameters.deployer
  val buildName = parameters.build.projectName
  val buildId = parameters.build.id
  val recipe = parameters.recipe.name
  val stage = parameters.stage

  def execute() {
    val taskList = tasks.toTaskList
    if (taskList.isEmpty) reporter.fail("No tasks were found to execute. Ensure the app(s) are in the list supported by this stage/host.")

    taskList.foreach { task =>
      reporter.taskContext(task) { taskLogger =>
        task.execute(taskLogger)
      }
    }
  }
}

case class DeployStoppedException(message:String) extends Exception(message)
