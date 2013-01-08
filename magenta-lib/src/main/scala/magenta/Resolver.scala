package magenta

import tasks.Task
import HostList._


case class RecipeTasks(recipe: String, tasks: List[Task])

object Resolver {

  def resolve( project: Project, deployInfo: DeployInfo, parameters: DeployParameters): List[Task] =
    resolveDetail(project, deployInfo, parameters).flatMap(_.tasks)

  def resolveDetail( project: Project, deployInfo: DeployInfo, parameters: DeployParameters): List[RecipeTasks] = {

    def resolveDependencies(recipeName: String): List[String] = {
      val recipe = project.recipes(recipeName)
      recipe.dependsOn.flatMap { resolveDependencies } :+ recipeName
    }

    def resolveRecipe(recipeName: String): List[Task] = {
      val recipe = project.recipes(recipeName)

      val tasksToRunBeforeApp = recipe.actionsBeforeApp.toList flatMap { _.resolve(deployInfo, parameters) }

      val perHostTasks = {
        for {
          action <- recipe.actionsPerHost
          tasks <- action.resolve(deployInfo.forParams(parameters), parameters)
        } yield {
          tasks
        }
      }
      if (!recipe.actionsPerHost.isEmpty && perHostTasks.isEmpty) throw new NoHostsFoundException

      val sortedPerHostTasks = perHostTasks.toSeq.sortBy(t =>
        t.taskHost.map(h => deployInfo.hosts.indexOf(h.copy(connectAs = None))).getOrElse(-1)
      )

      tasksToRunBeforeApp ++ sortedPerHostTasks
    }

    val dependencies = resolveDependencies(parameters.recipe.name)
    dependencies.distinct.map(recipeName => RecipeTasks(recipeName, resolveRecipe(recipeName)))
  }

  def possibleApps(project: Project, recipeName: String): String = {
    val recipe = project.recipes(recipeName)
    val appNames = for {
      action <- recipe.actionsPerHost
      app <- action.apps
    } yield app.name
    appNames.mkString(", ")
  }

}
class NoHostsFoundException extends Exception("No hosts found")




