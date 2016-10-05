package utils

import play.api.data.{FormError, Mapping}
import java.util.UUID
import play.api.data.Forms._
import play.api.data.format.Formatter
import play.api.data.format.Formats._

object Forms {
  val uuid: Mapping[UUID] = of[UUID](new Formatter[UUID] {
    override val format = Some("format.uuid", Nil)
    def bind(key: String, data: Map[String, String]) = {
      stringFormat.bind(key, data).right.flatMap { s =>
        scala.util.control.Exception
          .allCatch[UUID]
          .either(UUID.fromString(s))
          .left
          .map(e => Seq(FormError(key, "error.uuid", Nil)))
      }
    }
    def unbind(key: String, value: UUID) = Map(key -> value.toString)
  })
}
