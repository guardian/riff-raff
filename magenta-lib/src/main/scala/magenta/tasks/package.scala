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
    private def toDiEdges[T](nodes: List[T], pathInfo: PartialFunction[T, Label]): List[LDiEdge[T]] = {
      nodes match {
        case Nil => Nil
        case single :: Nil => Nil
        case first :: second :: tail =>
          (first ~+> second)(pathInfo.lift(first).getOrElse(NoLabel)) :: toDiEdges(second :: tail, pathInfo)
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

  implicit class RichTaskGraph(graph: TaskGraph) {
    def start: TaskGraph#NodeT = graph.get(StartMarker)
    def end: TaskGraph#NodeT = graph.get(EndMarker)

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

    def composite(graph2: TaskGraph): TaskGraph = {
      val maxPriority = graph.get(StartMarker).outgoing.flatMap(_.label match {
        case PathInfo(_, priority) => Some(priority)
        case NoLabel() => None
      }).max
      graph ++ graph2.get(StartMarker).outgoing.foldLeft(graph2){ case(g, edge) =>
        val newLabel = edge.label match {
          case PathInfo(name, priority) => PathInfo(name, priority + maxPriority)
          case NoLabel() => NoLabel
        }
        val newEdge = (edge.from.value ~+> edge.to.value)(newLabel)
        g - edge + newEdge
      }
    }
  }
}
