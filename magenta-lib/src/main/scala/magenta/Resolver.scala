package magenta

import tasks.Task


object Resolver {

  def resolve( project: Project, recipeName: String, hosts: List[Host], stage: Stage): List[Task] = {
    val recipe = project.recipes(recipeName)

    val dependenciesFromOtherRecipes = recipe.dependsOn.flatMap { resolve(project, _, hosts, stage) }

    val tasksToRunBeforeApp = recipe.actionsBeforeApp flatMap { resolveTasks(_, stage) }

    val perHostTasks = {
      if (!recipe.actionsPerHost.isEmpty && hosts.isEmpty) throw new NoHostsFoundException

      for {
        host <- hosts
        action <- recipe.actionsPerHost.filterNot(action => (action.apps & host.apps).isEmpty)
        tasks <- resolveTasks(action, stage, Some(host))
      } yield {
        tasks
      }
    }

    dependenciesFromOtherRecipes ++ tasksToRunBeforeApp ++ perHostTasks
  }
  
  private def resolveTasks(action : Action, stage: Stage,  hostOption: Option[Host] = None) = {
    (hostOption, action) match {
      case (Some(host), perHostAction: PerHostAction) => perHostAction.resolve(host)
      case (None, perAppAction: PerAppAction) => perAppAction.resolve(stage)
      case _ => sys.error("There is no sensible task for combination of %s and %s" format (hostOption, action))
    }
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




