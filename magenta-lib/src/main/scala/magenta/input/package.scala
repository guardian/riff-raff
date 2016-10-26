package magenta

import play.api.libs.json._

import scala.reflect.runtime.universe._

package object input {
  def checkedReads[T](underlyingReads: Reads[T])(implicit typeTag: TypeTag[T]): Reads[T] = new Reads[T] {
    def classFields[T: TypeTag]: Set[String] = typeOf[T].members.collect {
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

  // serialisation and de-serialisation code for deployment IDs
  val DEPLOYMENT_DELIMITER = '!'
  val FIELD_DELIMITER = '*'
  def idToStringRepresentation(d: List[DeploymentId]): String = {
    d.map(idToStringRepresentation).mkString(DEPLOYMENT_DELIMITER.toString)
  }
  def idToStringRepresentation(d: DeploymentId): String = {
    List(d.name, d.action, d.region, d.stack).mkString(FIELD_DELIMITER.toString)
  }
  def stringRepresentationToId(s: String): List[DeploymentId] = {
    def forDeployment(d: String) = d.split(FIELD_DELIMITER).toList match {
      case name :: action :: region :: stack :: Nil => Some(DeploymentId(name, action, stack, region))
      case _ => None
    }
    s.split(DEPLOYMENT_DELIMITER).toList.flatMap(forDeployment)
  }
}
