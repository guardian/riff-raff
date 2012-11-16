package test

import scala.Some
import net.liftweb.json._
import net.liftweb.json.Diff

case class RenderDiff(diff: Diff) {
  lazy val attributes = Map("changed" -> diff.changed, "added" -> diff.added, "deleted" -> diff.deleted)
  lazy val isEmpty = !attributes.values.exists(_ != JNothing)

  def renderJV(json: JValue): Option[String] = if (json == JNothing) None else Some(compact(render(json)))
  override def toString: String = {
    val jsonMap = attributes.mapValues(renderJV(_))
    jsonMap.flatMap { case (key: String, rendered: Option[String]) =>
      rendered.map{r => "%s: %s" format (key,r)}
    } mkString("\n")
  }
}

trait Utilities {
  def compareJson(from: String, to: String) = RenderDiff(Diff.diff(parse(from), parse(to)))
}
