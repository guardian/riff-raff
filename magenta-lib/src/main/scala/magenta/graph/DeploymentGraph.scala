package magenta.graph

import magenta.tasks.Task

case class Tasks(tasks: List[Task], name: String)

object DeploymentGraph {
  def apply(tasks: List[Task], name: String, priority: Int = 1): Graph[Tasks] = {
    val deploymentNode = MidNode(Tasks(tasks, name))
    Graph(StartNode ~> deploymentNode, deploymentNode ~> EndNode)
  }

  def toTaskList(taskGraph: Graph[Tasks]) = taskGraph.toList.flatMap(_.tasks)
}
