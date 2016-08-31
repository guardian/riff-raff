package magenta

package object graph {
  implicit class RichNodeSet(nodes: Set[Node]) {
    def filterDeploymentNodes: Set[DeploymentNode] = nodes.flatMap{
      case deploymentNode:DeploymentNode => Some(deploymentNode)
      case _ => None
    }
  }
  implicit class RichNodeList(nodes: List[Node]) {
    def filterDeploymentNodes: List[DeploymentNode] = nodes.flatMap{
      case deploymentNode:DeploymentNode => Some(deploymentNode)
      case _ => None
    }
  }
}
