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

  def resolveStacks(project: Project, parameters: DeployParameters): Seq[Stack] = {
    parameters.stacks match {
      case Nil => if (project.defaultStacks.nonEmpty) project.defaultStacks else Seq(UnnamedStack)
      case s => s
    }
  }

  def resolveRecipeTasks(recipe: Recipe, resources: DeploymentResources, target: DeployTarget): RecipeTasks = {
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
    val taskHostsInOriginalOrder = resources.lookup.hosts.all.filter(h => taskHosts.contains(h.copy(connectAs = None)))
    val groupedHosts = taskHostsInOriginalOrder.transposeBy(_.tags.getOrElse("group",""))
    val sortedPerHostTasks = perHostTasks.toList.sortBy(t =>
      t.taskHost.map(h => groupedHosts.indexOf(h.copy(connectAs = None))).getOrElse(-1)
    )

    RecipeTasks(recipe, tasksToRunBeforeApp, sortedPerHostTasks)
  }

  def resolveRecipeTree(project: Project, recipeName: String, resources: DeploymentResources,
    target: DeployTarget): RecipeTasksNode = {
    val recipe = project.recipes.getOrElse(recipeName, sys.error(s"Recipe '$recipeName' doesn't exist in your deploy.json file"))
    val recipeTasks = resolveRecipeTasks(recipe, resources, target)
    val children = recipe.dependsOn.map(resolveRecipeTree(project, _, resources, target))
    RecipeTasksNode(recipeTasks, children)
  }

  def resolveFilteredTree(project: Project, resourceLookup: Lookup, parameters: DeployParameters, deployReporter: DeployReporter, artifactClient: AmazonS3, stack: Stack): RecipeTasksNode = {
    val resources = DeploymentResources(deployReporter, resourceLookup, artifactClient)
    val target = DeployTarget(parameters, stack)
    val resolvedTree = resolveRecipeTree(project, parameters.recipe.name, resources, target)
    val filteredTree = resolvedTree.disable(_.invalid)
    filteredTree
  }

  def resolveStackTasks(project: Project, resourceLookup: Lookup, parameters: DeployParameters,
    deployReporter: DeployReporter, artifactClient: AmazonS3, stack: Stack): List[RecipeTasks] = {
    for {
      tasks <- resolveFilteredTree(project, resourceLookup, parameters, deployReporter, artifactClient, stack).toList.distinct
    } yield tasks
  }

  def resolveStackTaskGraph(project: Project, resourceLookup: Lookup, parameters: DeployParameters,
    deployReporter: DeployReporter, artifactClient: AmazonS3, stack: Stack): Graph[Deployment] = {
    val filteredTree = resolveFilteredTree(project, resourceLookup, parameters, deployReporter, artifactClient, stack)
    filteredTree.toGraph(s"${parameters.build.projectName}${stack.nameOption.map(" -> "+_).getOrElse("")}")
  }

  def resolveDetail(project: Project, resourceLookup: Lookup, parameters: DeployParameters,
    deployReporter: DeployReporter, artifactClient: AmazonS3): List[RecipeTasks] = {
    val stacks = resolveStacks(project, parameters)
    for {
      stack <- stacks.toList
      tasks <- resolveStackTasks(project, resourceLookup, parameters, deployReporter, artifactClient, stack)
    } yield tasks
  }

  def resolve(project: Project, resourceLookup: Lookup, parameters: DeployParameters, deployReporter: DeployReporter,
    artifactClient: AmazonS3): Graph[Deployment] = {
    resolveStacks(project, parameters).map { stack =>
      resolveStackTaskGraph(project, resourceLookup, parameters, deployReporter, artifactClient, stack)
    }.reduce(_ joinParallel _)
  }
}
class NoHostsFoundException extends Exception("No hosts found")
