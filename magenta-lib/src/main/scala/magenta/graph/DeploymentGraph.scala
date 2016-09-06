package magenta.graph

import magenta.tasks.Task

case class Deployment(tasks: List[Task], pathName: String)

object DeploymentGraph {
  def apply(tasks: List[Task], pathName: String, priority: Int = 1): Graph[Deployment] = {
    val deploymentNode = MidNode(Deployment(tasks, pathName))
    Graph(StartNode ~> deploymentNode, deploymentNode ~> EndNode)
  }

  def toTaskList(taskGraph: Graph[Deployment]) = taskGraph.toList.flatMap(_.tasks)
}
