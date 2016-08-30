package magenta

import scalax.collection.constrained._
import scalax.collection.constrained.constraints.Acyclic
import scalax.collection.edge.Implicits._
import scalax.collection.edge.LDiEdge

package object tasks {
  /** Constraint representing a DAG. */
  def dagConstraint = Acyclic withStringPrefix "DAG"

  /** Default (immutable) directed acyclic `Graph`. */
  type TaskGraph = Graph[TaskNode, LDiEdge]
  /** Companion module for default (immutable) directed acyclic `Graph`. */
  object TaskGraph extends CompanionAlias[LDiEdge](dagConstraint) {
    private def toDiEdges[T](nodes: List[T]): Set[LDiEdge[T]] = {
      nodes match {
        case Nil => Set.empty
        case single :: Nil => Set.empty
        case first :: second :: tail =>
          toDiEdges(second :: tail) + (first ~+> second)(None)
      }
    }

    private def toTaskGraph[T](edges: Set[LDiEdge[TaskNode]]): TaskGraph = {
      implicit val conf: Config = Acyclic
      from(Nil, edges)
    }

    def fromEdges(edges: LDiEdge[TaskNode]*): TaskGraph = {
      implicit val conf: Config = Acyclic
      from(Nil, edges)
    }

    def fromTaskList(tasks: List[Task], name: String): TaskGraph = {
      val taskReferences = tasks.zipWithIndex.map{case (t, i) => TaskReference(t, i, name)}
      val taskEdges: Set[LDiEdge[TaskNode]] = toDiEdges(taskReferences)
      val startEdge: LDiEdge[TaskNode] = (StartMarker ~+> taskReferences.head) (PathStart(name))
      val endEdge: LDiEdge[TaskNode] = (taskReferences.last ~+> EndMarker) (PathEnd(name))
      toTaskGraph(taskEdges + startEdge + endEdge)
    }
  }

  implicit class RichEdge(edge: TaskGraph#EdgeT) {
    def pathAnnotation: Option[PathAnnotation] = edge.label match {
      case pa:PathAnnotation => Some(pa)
      case _ => None
    }
    def pathStartPriority: Option[Int] = pathAnnotation.flatMap {
      case PathStart(_, priority) => Some(priority)
      case _ => None
    }
  }

  implicit class RichTaskGraph(graph: TaskGraph) {
    def start: TaskGraph#NodeT = graph.get(StartMarker)
    def end: TaskGraph#NodeT = graph.get(EndMarker)

    // traverse graph to build flat list
    def toTaskList: List[Task] = {
      def traverseFrom(node: TaskNode, visited: Set[TaskNode]): List[TaskNode] = {
        val predecessors: Set[TaskNode] = graph.get(node).diPredecessors.map(_.value)
        if ((predecessors -- visited).nonEmpty) {
          // if there are some predecessors of this node that we haven't yet visited then return empty list - we'll be back
          Nil
        } else {
          // if we've visited all the predecessors then follow all the successors
          val successors = graph.get(node).outgoing.toList.sortBy{ edge =>
            // order by the priority of the edge
            edge.pathStartPriority
          }.map(_.to)
          successors.foldLeft(List(node)){ case (acc, successor) =>
            acc ::: traverseFrom(successor, visited ++ acc)
          }
        }
      }

      val nodes = traverseFrom(graph.start.value, Set.empty)
      nodes.flatMap(_.taskReference).map(_.task)
    }

    def joinParallel(graph2: TaskGraph): TaskGraph = {
      val maxPriority = graph.start.outgoing.flatMap(_.pathStartPriority).max
      graph ++ graph2.start.outgoing.foldLeft(graph2){ case(g, edge) =>
        val newLabel = edge.pathAnnotation match {
          case Some(PathStart(name, priority)) => PathStart(name, priority + maxPriority)
          case other => other
        }
        val newEdge = (edge.from.value ~+> edge.to.value)(newLabel)
        g - edge + newEdge
      }
    }
  }
}
