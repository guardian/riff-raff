package magenta

package object graph {
  implicit class RichNodeSet[T](nodes: Set[Node[T]]) {
    def filterValueNodes: Set[ValueNode[T]] = nodes.collect {
      case valueNode: ValueNode[T] => valueNode
    }
    def values: Set[T] = filterValueNodes.map(_.value)
  }
  implicit class RichNodeList[T](nodes: List[Node[T]]) {
    def filterValueNodes: List[ValueNode[T]] = nodes.collect {
      case valueNode: ValueNode[T] => valueNode
    }
    def values: List[T] = filterValueNodes.map(_.value)
  }
  implicit class RichEdgeList[T](edges: List[Edge[T]]) {
    def replace(old: Edge[T], newEdges: List[Edge[T]]): List[Edge[T]] =
      edges.patch(edges.indexOf(old), newEdges, 1)
    def reprioritise: List[Edge[T]] = edges.zipWithIndex.map {
      case (edge, index) =>
        edge.copy(priority = index + 1)
    }
  }
}
