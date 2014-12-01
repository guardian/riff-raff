package docs

import org.clapper.markwrap.{MarkupType, MarkWrap}
import play.twirl.api.Html

import scala.util.Try

object MarkDown {
    def toHtml(markDown: String): Html = {
    Html(Try {
      MarkWrap.parserFor(MarkupType.Markdown).parseToHTML(markDown)
    }.getOrElse("Unable to parse markdown"))
  }
}