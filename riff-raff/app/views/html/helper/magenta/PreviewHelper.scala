package views.html.helper.magenta

import magenta.graph.{DeploymentTasks, Graph}
import magenta.input.DeploymentKey

object PreviewHelper {
  def dependencies(graph: Graph[(DeploymentKey, DeploymentTasks)], node: (DeploymentKey, DeploymentTasks)): List[(DeploymentKey, DeploymentTasks)] = {
    graph.predecessors(graph.get(node)).toList.values
  }
}
