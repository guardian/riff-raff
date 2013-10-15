package magenta.json

import net.liftweb.json.JsonAST.{JInt, JString, JValue}

trait JValueExtractable[T] {
  def extract(json: JValue): Option[T]
  def extractOption[T <: JValue, X](extract: T => Option[X])(json: JValue)(implicit t: reflect.ClassTag[T]) =
    t.unapply(json) flatMap (extract)
}
object JValueExtractable {
  implicit object StringExtractable extends JValueExtractable[String] {
    def extract(json: JValue) = extractOption(JString.unapply)(json)
  }
  implicit object IntExtractable extends JValueExtractable[Int]  {
    def extract(json: JValue) = extractOption(JInt.unapply _ andThen (_.map(_.toInt)))(json)
  }
}
