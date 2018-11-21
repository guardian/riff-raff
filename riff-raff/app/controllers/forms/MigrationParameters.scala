package controllers.forms

import play.api.data.Form
import play.api.data.Forms._

final case class MigrationParameters(collections: List[String], tables: List[String], limit: Option[Int], action: String)

object MigrationParameters {
  val form = Form[MigrationParameters](
    mapping(
      "collections" -> list(nonEmptyText),
      "tables"      -> list(nonEmptyText),
      "limit"       -> optional(number),
      "action"      -> nonEmptyText
    )(MigrationParameters.apply)(MigrationParameters.unapply)
  )
}
