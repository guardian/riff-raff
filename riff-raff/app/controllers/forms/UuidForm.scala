package controllers.forms

import play.api.data.Form
import play.api.data.Forms._

case class UuidForm(uuid: String, action: String)

object UuidForm {
  lazy val form = Form[UuidForm](
    mapping(
      "uuid" -> text(36, 36),
      "action" -> nonEmptyText
    )(UuidForm.apply)(UuidForm.unapply)
  )
}
