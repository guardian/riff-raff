package magenta.json

import net.liftweb.json.JsonAST._
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.JsonAST.JInt
import net.liftweb.json.JsonAST.JBool
import java.io.File

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
    def extract(json: JValue) = json match {
      case JInt(bigInt) => Some(bigInt.toInt)
      // Backwards compatible shim
      case JString(intString) => Some(intString.toInt)
      case _ => None
    }
  }
  implicit object BooleanExtractable extends JValueExtractable[Boolean] {
    def extract(json: JValue) = extractOption(JBool.unapply)(json)
  }
  implicit def ListExtractable[T](implicit jve: JValueExtractable[T]) = new JValueExtractable[List[T]] {
    def extract(json: JValue) = extractOption(JArray.unapply)(json) map (_.flatMap(jve.extract(_)))
  }
  implicit object FileExtractable extends JValueExtractable[File] {
    def extract(json: JValue) = extractOption(JString.unapply)(json) map (new File(_))
  }
}
