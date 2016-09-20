package magenta.graph

import magenta.tasks.Task

case class Deployment(tasks: List[Task], name: String)

object DeploymentGraph {
  def apply(tasks: List[Task], name: String, priority: Int = 1): Graph[Deployment] = {
    val deploymentNode = MidNode(Deployment(tasks, name))
    Graph(StartNode ~> deploymentNode, deploymentNode ~> EndNode)
  }

  def toTaskList(taskGraph: Graph[Deployment]) = taskGraph.toList.flatMap(_.tasks)
}
