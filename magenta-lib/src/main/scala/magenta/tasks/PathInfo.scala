package magenta.tasks

sealed trait TaskGraphLabel
case class Nowt() extends TaskGraphLabel
case class PathInfo(name: String, priority: Int = 1) extends TaskGraphLabel
