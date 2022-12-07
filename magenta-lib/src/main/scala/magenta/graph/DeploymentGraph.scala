package magenta.graph

import magenta.tasks.Task

case class DeploymentTasks(tasks: List[Task], name: String)

object DeploymentGraph {
  def apply(
      tasks: List[Task],
      name: String,
      priority: Int = 1
  ): Graph[DeploymentTasks] = {
    val deploymentNode = ValueNode(DeploymentTasks(tasks, name))
    Graph(StartNode ~> deploymentNode, deploymentNode ~> EndNode)
  }

  def toTaskList(taskGraph: Graph[DeploymentTasks]) =
    taskGraph.toList.flatMap(_.tasks)
}
