package docs

import org.clapper.markwrap.{MarkupType, MarkWrap}
import play.api.templates.Html

object MarkDown {
  val parser = MarkWrap.parserFor(MarkupType.Markdown)
  def toHtml(markDown: String): Html = {
    Html(parser.parseToHTML(markDown))
  }
}