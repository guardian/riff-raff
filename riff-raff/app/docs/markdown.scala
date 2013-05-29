package docs

import org.clapper.markwrap.{MarkupType, MarkWrap}
import play.api.templates.Html

object MarkDown {
    def toHtml(markDown: String): Html = {
    Html(MarkWrap.parserFor(MarkupType.Markdown).parseToHTML(markDown))
  }
}