package magenta

package object graph {
  implicit class RichNodeSet[T](nodes: Set[Node[T]]) {
    def filterMidNodes: Set[MidNode[T]] = nodes.collect{ case mn:MidNode[T] => mn }
  }
  implicit class RichNodeList[T](nodes: List[Node[T]]) {
    def filterMidNodes: List[MidNode[T]] = nodes.collect{ case mn:MidNode[T] => mn }
  }
  implicit class RichEdgeList[T](edges: List[Edge[T]]) {
    def replace(old: Edge[T], newEdges: List[Edge[T]]): List[Edge[T]] = {
      val before = edges.takeWhile(old != _)
      val after = edges.dropWhile(old != _).tail
      before ::: newEdges ::: after
    }
    def reprioritise: List[Edge[T]] = {
      edges.zipWithIndex.map {case (edge, index) =>
        edge.copy(priority = index + 1)
      }
    }
  }
}
