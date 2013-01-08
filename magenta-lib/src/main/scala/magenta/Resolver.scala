package magenta

import tasks.Task
import HostList._


case class RecipeTasks(recipe: Recipe, preTasks: List[Task], hostTasks: List[Task], disabled: Boolean = false) {
  lazy val hosts = tasks.flatMap(_.taskHost).map(_.copy(connectAs=None)).distinct
  lazy val tasks = if (disabled) Nil else preTasks ++ hostTasks
  lazy val recipeName = recipe.name
}

case class RecipeTasksNode(recipeTasks: RecipeTasks, children: List[RecipeTasksNode]) {
  def disable(f: RecipeTasks => Boolean): RecipeTasksNode = {
    if (f(recipeTasks))
      copy(recipeTasks = recipeTasks.copy(disabled=true), children = children.map(_.disable(_ => true)))
    else
      copy(children = children.map(_.disable(f)))
  }

  def toList: List[RecipeTasks] = children.flatMap(_.toList) ++ List(recipeTasks)
}

object Resolver {

  def resolve( project: Project, deployInfo: DeployInfo, parameters: DeployParameters): List[Task] =
    resolveDetail(project, deployInfo, parameters).flatMap(_.tasks)

  def resolveDetail( project: Project, deployInfo: DeployInfo, parameters: DeployParameters): List[RecipeTasks] = {

    def resolveTree(recipeName: String): RecipeTasksNode = {
      val recipe = project.recipes(recipeName)
      val recipeTasks = resolveRecipe(recipe)
      val children = recipe.dependsOn.map(resolveTree)
      RecipeTasksNode(recipeTasks, children)
    }

    def resolveDependencies(recipeName: String): List[String] = {
      val recipe = project.recipes(recipeName)
      recipe.dependsOn.flatMap { resolveDependencies } :+ recipeName
    }

    def resolveRecipe(recipe: Recipe): RecipeTasks = {
      val tasksToRunBeforeApp = recipe.actionsBeforeApp.toList flatMap { _.resolve(deployInfo, parameters) }

      val perHostTasks = {
        for {
          action <- recipe.actionsPerHost
          tasks <- action.resolve(deployInfo.forParams(parameters), parameters)
        } yield {
          tasks
        }
      }

      val sortedPerHostTasks = perHostTasks.toList.sortBy(t =>
        t.taskHost.map(h => deployInfo.hosts.indexOf(h.copy(connectAs = None))).getOrElse(-1)
      )

      RecipeTasks(recipe, tasksToRunBeforeApp, sortedPerHostTasks)
    }

    val resolvedTree = resolveTree(parameters.recipe.name)
    val filteredTree = resolvedTree.disable(rt => !rt.recipe.actionsPerHost.isEmpty && rt.hostTasks.isEmpty)
    filteredTree.toList.distinct
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




