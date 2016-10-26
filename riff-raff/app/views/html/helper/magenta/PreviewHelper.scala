package views.html.helper.magenta

import magenta.graph.{DeploymentTasks, Graph}
import magenta.input.DeploymentId

object PreviewHelper {
  def dependencies(graph: Graph[(DeploymentId, DeploymentTasks)], node: (DeploymentId, DeploymentTasks)): List[(DeploymentId, DeploymentTasks)] = {
    graph.predecessors(graph.get(node)).toList.values
  }
}
