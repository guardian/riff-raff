package magenta.json

import org.json4s._

import scala.reflect.ClassTag

trait JValueExtractable[T] {
  def extract(json: JValue): Option[T]
  def extractOption[T <: JValue, X](extract: T => Option[X])(json: JValue)(implicit t: ClassTag[T]) =
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
  implicit def MapExtractable[V](implicit jve: JValueExtractable[V]) = new JValueExtractable[Map[String,V]] {
    def extract(json: JValue): Option[Map[String, V]] = {
      val JObject(fields) = json

      val kvs = for {
        field <- fields
      } yield {
        val JField(k, v) = field
        k -> jve.extract(v)
      }

      if (kvs.isEmpty || !kvs.forall{case (_, x) => x.isDefined}) None
      else Some(Map(kvs: _*).mapValues(_.get))
    }
  }
}
