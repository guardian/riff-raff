package docs

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.{AbstractVisitor, Link}
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

import play.api.mvc.Call
import play.twirl.api.Html

object MarkDownParser {

  private val AbsoluteUrl = "^[a-zA-Z]+://".r

  private val extensions = java.util.List.of(
    AutolinkExtension.create(),
    StrikethroughExtension.create(),
    TablesExtension.create(),
    TaskListItemsExtension.create()
  )

  private val parser = Parser.builder().extensions(extensions).build()
  private val renderer = HtmlRenderer.builder().extensions(extensions).build()

  def toHtml(
      markDown: String,
      rewriteRelativeUrl: Option[String => Call] = None
  ): Html = {
    val document = parser.parse(markDown)
    rewriteRelativeUrl.foreach { urlToCall =>
      document.accept(new AbstractVisitor {
        override def visit(link: Link): Unit = {
          val url = link.getDestination
          if (AbsoluteUrl.findFirstIn(url).isEmpty && !url.startsWith("/"))
            link.setDestination(urlToCall(url).path)
          visitChildren(link)
        }
      })
    }
    Html(renderer.render(document))
  }
}
