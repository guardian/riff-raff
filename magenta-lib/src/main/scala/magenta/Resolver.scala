package magenta

import tasks.Task
import HostList._


object Resolver {

  def resolve( project: Project, deployInfo: DeployInfo, parameters: DeployParameters): List[Task] = {

    def resolveRecipe(recipeName: String): List[Task] = {
      val recipe = project.recipes(recipeName)

      val hosts = deployInfo.hosts
//      val filteredHosts:HostList = if (hostList.isEmpty) hosts else hosts.filter(hostList contains _.name)
//val stageHosts = {
//  val stageHosts = allHosts.filterByStage(parameters.stage)
//  MessageBroker.verbose("All possible hosts in stage:\n" + stageHosts.dump)
//  stageHosts
//}

      val dependenciesFromOtherRecipes = recipe.dependsOn.flatMap { resolveRecipe(_) }

      val tasksToRunBeforeApp = recipe.actionsBeforeApp flatMap { resolveTasks(_, parameters, deployInfo) }

      val perHostTasks = {
        if (!recipe.actionsPerHost.isEmpty && hosts.isEmpty) throw new NoHostsFoundException

        for {
          action <- recipe.actionsPerHost
          tasks <- resolveTasks(action, parameters, deployInfo)
        } yield {
          tasks
        }
      }
      val sortedPerHostTasks = perHostTasks.toSeq.sortBy(t => t.taskHost.map(_.name).getOrElse(""))

      dependenciesFromOtherRecipes ++ tasksToRunBeforeApp ++ sortedPerHostTasks
    }

    resolveRecipe(parameters.recipe.name)
  }
  
  private def resolveTasks(action : Action, parameters: DeployParameters,  deployInfo: DeployInfo) = {
      action.resolve(deployInfo, parameters)

//    (hostOption, action) match {
//      case (Some(host), perHostAction: PerHostAction) => perHostAction.resolve(host)
//      case (None, perAppAction: PerAppAction) => perAppAction.resolve(parameters)
//      case _ => sys.error("There is no sensible task for combination of %s and %s" format (hostOption, action))
//    }
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




