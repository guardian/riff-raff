package magenta.tasks

import magenta.Stack

import scala.util.Random
import scalax.collection.GraphEdge.DiEdge
import scalax.collection.GraphPredef._
import scalax.collection.constrained._
import scalax.collection.constrained.constraints.Acyclic

trait TaskNode {
  def taskReference: Option[TaskReference] = None
}
case class TaskReference(task: Task, index: Int, stack: Stack) extends TaskNode {
  override val taskReference = Some(this)
  lazy val id = s"$stack/$index"
}
case object StartMarker extends TaskNode
case object EndMarker extends TaskNode

object TaskGraph {
  def toDiEdges[T](nodes: List[T]): List[DiEdge[T]] = {
    nodes match {
      case Nil => Nil
      case single :: Nil => Nil
      case first :: second :: tail =>
        first ~> second :: toDiEdges(second :: tail)
    }
  }

  def toDAG[T](edges: List[DiEdge[T]]): DAG[T] = {
    implicit val conf: Config = Acyclic
    Graph(edges:_*)
  }

  def toTaskGraph(tasks: List[Task], stack: Stack): DAG[TaskNode] = {
    val taskReferences = tasks.zipWithIndex.map{case (t, i) => TaskReference(t, i, stack)}
    val edges = TaskGraph.toDiEdges(StartMarker :: (taskReferences :+ EndMarker))
    TaskGraph.toDAG(edges)
  }

  // traverse graph to build flat list
  def toTaskList(taskGraph: DAG[TaskNode], stackParameters: Seq[Stack] = Nil): List[Task] = {
    def traverseFrom(node: TaskNode, visited: Set[TaskNode]): List[TaskNode] = {
      val predecessors: Set[TaskNode] = taskGraph.get(node).diPredecessors.map(_.value)
      if ((predecessors -- visited).nonEmpty) {
        // if there are some predecessors of this node that we haven't yet visited then return empty list - we'll be back
        Nil
      } else {
        // if we've visited all the predecessors then follow all the successors
        val successors = taskGraph.get(node).diSuccessors.toList.sortBy{ succ =>
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

    val nodes = traverseFrom(taskGraph.get(StartMarker), Set.empty)
    nodes.flatMap(_.taskReference).map(_.task)
  }
}
