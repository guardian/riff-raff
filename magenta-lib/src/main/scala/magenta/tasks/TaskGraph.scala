package magenta.tasks

import magenta.Stack

trait TaskNode {
  def taskReference: Option[TaskReference] = None
}
case class TaskReference(task: Task, index: Int, stack: Stack) extends TaskNode {
  override val taskReference = Some(this)
  lazy val id = s"$stack/$index"
}
case object StartMarker extends TaskNode
case object EndMarker extends TaskNode

