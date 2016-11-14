package magenta

import magenta.graph.{DeploymentTasks, DeploymentGraph, Graph}
import magenta.tasks._

case class RecipeTasks(recipe: Recipe, tasks: List[Task]) {
  lazy val hosts = tasks.flatMap(_.taskHost).map(_.copy(connectAs = None)).distinct
  lazy val recipeName = recipe.name
}

case class RecipeTasksNode(recipeTasks: RecipeTasks, children: List[RecipeTasksNode]) {
  def toList: List[RecipeTasks] = children.flatMap(_.toList) ++ List(recipeTasks)

  def toGraph(name: String): Graph[DeploymentTasks] = {
    val thisGraph: Graph[DeploymentTasks] = DeploymentGraph(recipeTasks.tasks, s"$name (${recipeTasks.recipeName})")

    val childGraph: Graph[DeploymentTasks] =
      children.map(_.toGraph(name)).reduceLeftOption(_ joinParallel _).getOrElse(Graph.empty)

    val graph = childGraph joinSeries thisGraph
    graph
  }
}

object Resolver {

  def resolve(project: Project, parameters: DeployParameters, resources: DeploymentResources, region: Region): Graph[DeploymentTasks] = {
    resolveStacks(project, parameters, resources.reporter).map { stack =>
      val stackTasks = resolveStack(project, parameters, resources, stack, region).flatMap(_.tasks)
      DeploymentGraph(stackTasks, s"${parameters.build.projectName}${stack.nameOption.map(" -> " + _).getOrElse("")}")
    }.reduce(_ joinParallel _)
  }

  def resolveDetail(project: Project, parameters: DeployParameters, resources: DeploymentResources, region: Region): List[RecipeTasks] = {
    val stacks = resolveStacks(project, parameters, resources.reporter)
    for {
      stack <- stacks.toList
      tasks <- resolveStack(project, parameters, resources, stack, region)
    } yield tasks
  }

  def resolveStack(project: Project, parameters: DeployParameters, resources: DeploymentResources, stack: Stack, region: Region): List[RecipeTasks] = {

    def resolveTree(recipeName: String, resources: DeploymentResources, target: DeployTarget): RecipeTasksNode = {
      val recipe = project.recipes.getOrElse(recipeName, sys.error(s"Recipe '$recipeName' doesn't exist in your deploy.json file"))
      val recipeTasks = resolveRecipe(recipe, resources, target)
      val children = recipe.dependsOn.map(resolveTree(_, resources, target))
      RecipeTasksNode(recipeTasks, children)
    }

    def resolveRecipe(recipe: Recipe, resources: DeploymentResources, target: DeployTarget): RecipeTasks = {
      val tasks = for {
        deploymentStep <- recipe.deploymentSteps
        tasks <- deploymentStep.resolve(resources, target)
      } yield {
        tasks
      }

      RecipeTasks(recipe, tasks.toList)
    }

    for {
      tasks <- {
        val target = DeployTarget(parameters, stack, region)
        val resolvedTree = resolveTree(parameters.recipe.name, resources, target)
        resolvedTree.toList.distinct
      }
    } yield tasks
  }

  def resolveStacks(project: Project, parameters: DeployParameters, reporter: DeployReporter): Seq[Stack] = {
    parameters.stacks match {
      case Nil if project.defaultStacks.nonEmpty => project.defaultStacks
      case Nil =>
        reporter.warning("DEPRECATED: Your deploy.json should always specify stacks using the top level defaultStacks parameter. Not doing so means that stacks are not taken into account when determining which AWS credentials to use and therefore which AWS account to deploy to.")
        Seq(UnnamedStack)
      case s => s
    }
  }
}

class NoHostsFoundException extends Exception("No hosts found")
