package magenta.tasks

sealed trait Label
case class NoLabel() extends Label
case class PathInfo(name: String, priority: Int = 1) extends Label
