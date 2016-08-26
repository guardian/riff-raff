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
    private def toDiEdges[T](nodes: List[T], pathInfo: PartialFunction[T, TaskGraphLabel]): List[LDiEdge[T]] = {
      nodes match {
        case Nil => Nil
        case single :: Nil => Nil
        case first :: second :: tail =>
          (first ~+> second)(pathInfo.lift(first).getOrElse(Nowt)) :: toDiEdges(second :: tail, pathInfo)
      }
    }

    private def toTaskGraph[T](edges: List[LDiEdge[TaskNode]]): TaskGraph = {
      implicit val conf: Config = Acyclic
      from(Nil, edges)
    }

    def apply(tasks: List[Task], stack: Stack): TaskGraph = {
      val taskReferences = tasks.zipWithIndex.map{case (t, i) => TaskReference(t, i, stack)}
      val nodeToInfo = stack.nameOption.map(PathInfo(_)).map(StartMarker ->).toMap[TaskNode, PathInfo]
      val edges = toDiEdges(StartMarker :: (taskReferences :+ EndMarker), nodeToInfo)
      toTaskGraph(edges)
    }
  }

  implicit class RichEdge(edge: TaskGraph#EdgeT) {
    def taskGraphLabel: TaskGraphLabel = edge.label.asInstanceOf[TaskGraphLabel]
    def pathInfo: Option[PathInfo] = taskGraphLabel match {
      case pi:PathInfo => Some(pi)
      case Nowt() => None
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
            edge.pathInfo.map(_.priority)
          }.map(_.to)
          successors.foldLeft(List(node)){ case (acc, successor) =>
            acc ::: traverseFrom(successor, visited ++ acc)
          }
        }
      }

      val nodes = traverseFrom(graph.start, Set.empty)
      nodes.flatMap(_.taskReference).map(_.task)
    }

    def joinParallel(graph2: TaskGraph): TaskGraph = {
      val maxPriority = graph.start.outgoing.flatMap(_.pathInfo).map(_.priority).max
      graph ++ graph2.start.outgoing.foldLeft(graph2){ case(g, edge) =>
        val newLabel = edge.taskGraphLabel match {
          case PathInfo(name, priority) => PathInfo(name, priority + maxPriority)
          case Nowt() => Nowt()
        }
        val newEdge = (edge.from.value ~+> edge.to.value)(newLabel)
        g - edge + newEdge
      }
    }
  }
}
