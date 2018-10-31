package magenta

import play.api.libs.json._

import scala.reflect.runtime.universe._

package object input {
  def checkedReads[T](underlyingReads: Reads[T])(implicit typeTag: TypeTag[T]): Reads[T] = new Reads[T] {
    def classFields[T: TypeTag]: Set[String] = typeOf[T].members.collect {
      case m: MethodSymbol if m.isCaseAccessor => m.name.decodedName.toString
    }.toSet
    def reads(json: JsValue): JsResult[T] = {
      def fieldNames(map: Map[String, _]): Set[String] = map.keySet.filterNot(_.startsWith("$"))
      val caseClassFields = classFields[T]
      json match {
        case JsObject(fields) if (fieldNames(fields.toMap) -- caseClassFields).nonEmpty =>
          JsError(s"Unexpected fields provided: ${(fieldNames(fields.toMap) -- caseClassFields).mkString(", ")}")
        case _ => underlyingReads.reads(json)
      }
    }
  }
}
