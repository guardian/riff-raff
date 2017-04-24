package utils

import com.gu.management.Loggable
import magenta.input.DeploymentKey
import play.api.data.FormError
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.data.format.Formatter

object Forms extends Loggable {
  val deploymentKey = of[DeploymentKey](new Formatter[DeploymentKey] {
    def bind(key: String, data: Map[String, String]) = {
      stringFormat.bind(key, data).right.flatMap { s =>
        DeploymentKey.fromString(s).toRight(Seq(FormError("form.deployment-key", "Couldn't parse as deployment key")))
      }
    }
    def unbind(key: String, value: DeploymentKey) = Map(key -> DeploymentKey.asString(value))
  })

  val deploymentKeyList = of[List[DeploymentKey]](new Formatter[List[DeploymentKey]] {
    def bind(key: String, data: Map[String, String]) = {
      stringFormat.bind(key, data).right.flatMap { s =>
        Right(DeploymentKey.fromStringToList(s))
      }
    }
    def unbind(key: String, value: List[DeploymentKey]) = Map(key -> DeploymentKey.asString(value))
  })
}
