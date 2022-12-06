package controllers.forms

import play.api.data.Form
import play.api.data.Forms._
import play.api.mvc.QueryStringBindable
import cats._, cats.implicits._

final case class MigrationParameters(limit: Option[Int], action: String)

object MigrationParameters {
  val form = Form[MigrationParameters](
    mapping(
      "limit" -> optional(number),
      "action" -> nonEmptyText
    )(MigrationParameters.apply)(MigrationParameters.unapply)
  )

  implicit def queryStringBindable(implicit
      S: QueryStringBindable[String],
      L: QueryStringBindable[List[String]],
      O: QueryStringBindable[Option[Int]]
  ) = new QueryStringBindable[MigrationParameters] {
    override def bind(
        key: String,
        params: Map[String, Seq[String]]
    ): Option[Either[String, MigrationParameters]] =
      for {
        limit <- O.bind("limit", params)
        action <- S.bind("action", params)
      } yield {
        (limit, action).mapN(MigrationParameters.apply)
      }

    override def unbind(key: String, params: MigrationParameters): String = {
      O.unbind("limit", params.limit) + "&" +
        S.unbind("action", params.action)
    }
  }

}
