package utils

import com.gu.management.Loggable
import magenta.input.DeploymentId
import play.api.data.FormError
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.data.format.Formatter

object Forms extends Loggable {
  val deploymentId = of[DeploymentId](new Formatter[DeploymentId] {
    def bind(key: String, data: Map[String, String]) = {
      stringFormat.bind(key, data).right.flatMap{ s =>
        stringToId(s).toRight(Seq(FormError("form.deployment-id", "Couldn't parse as deployment ID")))
      }
    }
    def unbind(key: String, value: DeploymentId) = {
      val output = Map(key -> idToString(value))
      logger.info(s"deploymentId/unbind: $output")
      output
    }
  })

  val deploymentIdList = of[List[DeploymentId]](new Formatter[List[DeploymentId]] {
    def bind(key: String, data: Map[String, String]) = {
      stringFormat.bind(key, data).right.flatMap{ s =>
        Right(stringToIdList(s))
      }
    }
    def unbind(key: String, value: List[DeploymentId]) = {
      val output = Map(key -> idToString(value))
      logger.info(s"deploymentIdList/unbind: $output")
      output
    }
  })

  // serialisation and de-serialisation code for deployment IDs
  val DEPLOYMENT_DELIMITER = '!'
  val FIELD_DELIMITER = '*'
  def idToString(d: List[DeploymentId]): String = {
    d.map(idToString).mkString(DEPLOYMENT_DELIMITER.toString)
  }
  def idToString(d: DeploymentId): String = {
    List(d.name, d.action, d.region, d.stack).mkString(FIELD_DELIMITER.toString)
  }
  def stringToId(s: String) = s.split(FIELD_DELIMITER).toList match {
    case name :: action :: region :: stack :: Nil => Some(DeploymentId(name, action, stack, region))
    case _ => None
  }
  def stringToIdList(s: String): List[DeploymentId] = s.split(DEPLOYMENT_DELIMITER).toList.flatMap(stringToId)
}
