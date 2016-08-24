package magenta

import scalax.collection.constrained._

package object tasks {
  implicit class RichTaskGraph(graph: DAG[TaskNode]) {
    // traverse graph to build flat list
    def toTaskList(stackParameters: Seq[Stack] = Nil): List[Task] = {
      def traverseFrom(node: TaskNode, visited: Set[TaskNode]): List[TaskNode] = {
        val predecessors: Set[TaskNode] = graph.get(node).diPredecessors.map(_.value)
        if ((predecessors -- visited).nonEmpty) {
          // if there are some predecessors of this node that we haven't yet visited then return empty list - we'll be back
          Nil
        } else {
          // if we've visited all the predecessors then follow all the successors
          val successors = graph.get(node).diSuccessors.toList.sortBy{ succ =>
            val sortOption = succ.value.taskReference.map { ref =>
              // sort on the location of this stack in the parameter list and then the task ID (meaningless but deterministic)
              (stackParameters.indexOf(ref.stack), ref.id)
            }
            sortOption
          }
          successors.foldLeft(List(node)){ case (acc, successor) =>
            acc ::: traverseFrom(successor, visited ++ acc)
          }
        }
      }

      val nodes = traverseFrom(graph.get(StartMarker), Set.empty)
      nodes.flatMap(_.taskReference).map(_.task)
    }
  }
}
