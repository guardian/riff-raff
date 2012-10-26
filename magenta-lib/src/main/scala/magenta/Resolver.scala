package magenta

import tasks.Task
import HostList._


object Resolver {

  def resolve( project: Project, deployInfo: DeployInfo, parameters: DeployParameters): List[Task] = {

    def resolveRecipe(recipeName: String): List[Task] = {
      val recipe = project.recipes(recipeName)

      val dependenciesFromOtherRecipes = recipe.dependsOn.flatMap { resolveRecipe(_) }

      val tasksToRunBeforeApp = recipe.actionsBeforeApp flatMap { _.resolve(deployInfo, parameters) }

      val perHostTasks = {
        for {
          action <- recipe.actionsPerHost
          tasks <- action.resolve(deployInfo.forParams(parameters), parameters)
        } yield {
          tasks
        }
      }
      if (!recipe.actionsPerHost.isEmpty && perHostTasks.isEmpty) throw new NoHostsFoundException

      val sortedPerHostTasks = perHostTasks.toSeq.sortBy(t => t.taskHost.map(_.name).getOrElse(""))

      dependenciesFromOtherRecipes ++ tasksToRunBeforeApp ++ sortedPerHostTasks
    }

    resolveRecipe(parameters.recipe.name)
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




