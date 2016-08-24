package magenta.tasks

import magenta.Stack

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
  private def toDiEdges[T](nodes: List[T]): List[DiEdge[T]] = {
    nodes match {
      case Nil => Nil
      case single :: Nil => Nil
      case first :: second :: tail =>
        first ~> second :: toDiEdges(second :: tail)
    }
  }

  private def toDAG[T](edges: List[DiEdge[T]]): DAG[T] = {
    implicit val conf: Config = Acyclic
    Graph(edges:_*)
  }

  def apply(tasks: List[Task], stack: Stack): DAG[TaskNode] = {
    val taskReferences = tasks.zipWithIndex.map{case (t, i) => TaskReference(t, i, stack)}
    val edges = TaskGraph.toDiEdges(StartMarker :: (taskReferences :+ EndMarker))
    TaskGraph.toDAG(edges)
  }
}

