package magenta.tasks

sealed trait PathAnnotation {
  def name: String
}
case class PathStart(name: String, priority: Int = 1) extends PathAnnotation
case class PathEnd(name: String) extends PathAnnotation
