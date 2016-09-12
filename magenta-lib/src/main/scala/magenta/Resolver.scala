package magenta

import com.amazonaws.services.s3.AmazonS3
import magenta.graph.{Deployment, DeploymentGraph, Graph}
import magenta.tasks._

case class RecipeTasks(recipe: Recipe, preTasks: List[Task], hostTasks: List[Task], disabled: Boolean = false) {
  lazy val hosts = tasks.flatMap(_.taskHost).map(_.copy(connectAs=None)).distinct
  lazy val tasks = if (disabled) Nil else preTasks ++ hostTasks
  lazy val recipeName = recipe.name
  // invalid if there are host-tasks but not any hosts to apply them to :(
  lazy val invalid = recipe.actionsPerHost.nonEmpty && hostTasks.isEmpty
}

case class RecipeTasksNode(recipeTasks: RecipeTasks, children: List[RecipeTasksNode]) {
  def disable(f: RecipeTasks => Boolean): RecipeTasksNode = {
    if (f(recipeTasks))
      copy(recipeTasks = recipeTasks.copy(disabled=true), children = children.map(_.disable(_ => true)))
    else
      copy(children = children.map(_.disable(f)))
  }

  def toList: List[RecipeTasks] = children.flatMap(_.toList) ++ List(recipeTasks)

  def toGraph(name: String): Graph[Deployment] = {
    val thisGraph: Graph[Deployment] =
      if (recipeTasks.invalid) Graph.empty
      else DeploymentGraph(recipeTasks.tasks, s"$name (${recipeTasks.recipeName})")

    val childGraph: Graph[Deployment] =
      children.map(_.toGraph(name)).reduceLeftOption(_ joinParallel _).getOrElse(Graph.empty)

    val graph = childGraph joinSeries thisGraph
    graph
  }
}

object Resolver {

  def resolve( project: Project, resourceLookup: Lookup, parameters: DeployParameters, deployReporter: DeployReporter, artifactClient: AmazonS3): Graph[Deployment] = {
    resolveStacks(project, parameters).map { stack =>
      val stackTasks = resolveStack(project, resourceLookup, parameters, deployReporter, artifactClient, stack).flatMap(_.tasks)
      DeploymentGraph(stackTasks, s"${parameters.build.projectName}${stack.nameOption.map(" -> "+_).getOrElse("")}")
    }.reduce(_ joinParallel _)
    }

  def resolveDetail( project: Project, resourceLookup: Lookup, parameters: DeployParameters, deployReporter: DeployReporter, artifactClient: AmazonS3): List[RecipeTasks] = {
    val stacks = resolveStacks(project, parameters)
    for {
      stack <- stacks.toList
      tasks <- resolveStack(project, resourceLookup, parameters, deployReporter, artifactClient, stack)
    } yield tasks
  }

  def resolveStack( project: Project, resourceLookup: Lookup, parameters: DeployParameters, deployReporter: DeployReporter, artifactClient: AmazonS3, stack: Stack): List[RecipeTasks] = {

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

    for {
      tasks <- {
    val resources = DeploymentResources(deployReporter, resourceLookup, artifactClient)
    val target = DeployTarget(parameters, stack)
        val resolvedTree = resolveTree(parameters.recipe.name, resources, target)
        val filteredTree = resolvedTree.disable(_.invalid)
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
