package magenta

import com.amazonaws.services.s3.AmazonS3
import tasks.Task

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

  def resolve( project: Project, resourceLookup: Lookup, parameters: DeployParameters, deployReporter: DeployReporter, artifactClient: AmazonS3): List[Task] =
    resolveDetail(project, resourceLookup, parameters, deployReporter, artifactClient).flatMap(_.tasks)

  def resolveDetail( project: Project, resourceLookup: Lookup, parameters: DeployParameters, deployReporter: DeployReporter, artifactClient: AmazonS3): List[RecipeTasks] = {

    def resolveTree(recipeName: String, resources: DeploymentResources, target: DeployTarget): RecipeTasksNode = {
      val recipe = project.recipes.getOrElse(recipeName, sys.error(s"Recipe '$recipeName' doesn't exist in your deploy.json file"))
      val recipeTasks = resolveRecipe(recipe, resources, target)
      val children = recipe.dependsOn.map(resolveTree(_, resources, target))
      RecipeTasksNode(recipeTasks, children)
    }

    def resolveRecipe(recipe: Recipe, resources: DeploymentResources, target: DeployTarget): RecipeTasks = {
      val tasksToRunBeforeApp = recipe.actionsBeforeApp.toList flatMap { _.resolve(resources, target) }

      val perHostTasks = {
        for {
          action <- recipe.actionsPerHost
          tasks <- action.resolve(resources, target)
        } yield {
          tasks
        }
      }

      val taskHosts = perHostTasks.flatMap(_.taskHost).toSet
      val taskHostsInOriginalOrder = resourceLookup.hosts.all.filter(h => taskHosts.contains(h.copy(connectAs = None)))
      val groupedHosts = taskHostsInOriginalOrder.transposeBy(_.tags.getOrElse("group",""))
      val sortedPerHostTasks = perHostTasks.toList.sortBy(t =>
        t.taskHost.map(h => groupedHosts.indexOf(h.copy(connectAs = None))).getOrElse(-1)
      )

      RecipeTasks(recipe, tasksToRunBeforeApp, sortedPerHostTasks)
    }

    val stacks = resolveStacks(project, parameters)

    for {
      stack <- stacks.toList
      tasks <- {
        val resources = DeploymentResources(deployReporter, resourceLookup, artifactClient)
        val target = DeployTarget(parameters, stack)
        val resolvedTree = resolveTree(parameters.recipe.name, resources, target)
        val filteredTree = resolvedTree.disable(rt => rt.recipe.actionsPerHost.nonEmpty && rt.hostTasks.isEmpty)
        filteredTree.toList.distinct
      }
    } yield tasks
  }


  def resolveStacks(project: Project, parameters: DeployParameters): Seq[Stack] = {
    parameters.stacks match {
      case Nil => if (project.defaultStacks.nonEmpty) project.defaultStacks else Seq(UnnamedStack)
      case s => s
    }
  }
}
class NoHostsFoundException extends Exception("No hosts found")




