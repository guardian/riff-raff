package controllers.forms

import play.api.data.Form
import play.api.data.Forms._
import play.api.mvc.QueryStringBindable
import scalaz._, Scalaz._

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

  implicit def queryStringBindable(implicit 
    S: QueryStringBindable[String],
    L: QueryStringBindable[List[String]],
    O: QueryStringBindable[Option[Int]]
  ) = new QueryStringBindable[MigrationParameters] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, MigrationParameters]] = 
      for {
        collections <- L.bind("collections", params)
        tables      <- L.bind("tables", params)
        limit       <- O.bind("limit", params)
        action      <- S.bind("action", params)
      } yield {
        (collections |@| tables |@| limit |@| action)(MigrationParameters(_, _, _, _))
      }

    override def unbind(key: String, params: MigrationParameters): String = {
      L.unbind("collections", params.collections) + "&" + 
      L.unbind("tables", params.tables) + "&" +
      O.unbind("limit", params.limit) + "&" +
      S.unbind("action", params.action)
    }
  }

}
