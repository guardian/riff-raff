package magenta

package object graph {
  implicit class RichNodeSet[T](nodes: Set[Node[T]]) {
    def filterMidNodes: Set[MidNode[T]] = nodes.flatMap{
      case deploymentNode:MidNode[T] => Some(deploymentNode)
      case _ => None
    }
  }
  implicit class RichNodeList[T](nodes: List[Node[T]]) {
    def filterMidNodes: List[MidNode[T]] = nodes.flatMap{
      case deploymentNode:MidNode[T] => Some(deploymentNode)
      case _ => None
    }
  }
}
