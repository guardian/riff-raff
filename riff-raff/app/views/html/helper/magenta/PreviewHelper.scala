package views.html.helper.magenta

import java.net.URLEncoder

import magenta.graph.{DeploymentTasks, Graph}
import magenta.input.Deployment

object PreviewHelper {
  def safeId(input:String): String = URLEncoder.encode(input, "UTF-8")
  def dependencies(graph: Graph[(Deployment, DeploymentTasks)], node: (Deployment, DeploymentTasks)): List[(Deployment, DeploymentTasks)] = {
    graph.predecessors(graph.get(node)).toList.values
  }
}
