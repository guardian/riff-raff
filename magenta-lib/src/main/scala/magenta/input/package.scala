package magenta

import play.api.libs.json._

import scala.reflect.runtime.universe._

package object input {
  def checkedReads[T](underlyingReads: Reads[T])(implicit typeTag: TypeTag[T]): Reads[T] = new Reads[T] {
    def classFields[T: TypeTag]: Set[String] =
      typeOf[T].members.collect {
        case m: MethodSymbol if m.isCaseAccessor => m.name.decodedName.toString
      }.toSet
    def reads(json: JsValue): JsResult[T] = {
      val caseClassFields = classFields[T]
      json match {
        case JsObject(fields) if (fields.keySet -- caseClassFields).nonEmpty =>
          JsError(s"Unexpected fields provided: ${(fields.keySet -- caseClassFields).mkString(", ")}")
        case _ => underlyingReads.reads(json)
      }
    }
  }
}
