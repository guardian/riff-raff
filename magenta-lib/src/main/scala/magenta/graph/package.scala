package magenta

package object graph {
  implicit class RichNodeSet[T](nodes: Set[Node[T]]) {
    def filterMidNodes: Set[MidNode[T]] = nodes.collect{ case mn:MidNode[T] => mn }
  }
  implicit class RichNodeList[T](nodes: List[Node[T]]) {
    def filterMidNodes: List[MidNode[T]] = nodes.collect{ case mn:MidNode[T] => mn }
  }
}
