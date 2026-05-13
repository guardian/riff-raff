package docs

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.{AbstractVisitor, Link}
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

import play.twirl.api.Html

import scala.jdk.CollectionConverters._
import scala.util.Try

object MarkDownParser {

  private val AbsoluteUrl = "^[a-zA-Z]+://".r

  private def isRelativeUrl(url: String): Boolean =
    AbsoluteUrl.findPrefixOf(url).isEmpty && !url.startsWith("/")

  private val extensions = List(
    AutolinkExtension.create(),
    StrikethroughExtension.create(),
    TablesExtension.create(),
    TaskListItemsExtension.create()
  ).asJava

  private val parser = Parser.builder().extensions(extensions).build()
  private val renderer = HtmlRenderer.builder().extensions(extensions).build()

  def toHtml(
      markDown: String,
      rewriteRelativeUrl: Option[String => String] = None
  ): Html = {
    Html(Try {
      val document = parser.parse(markDown)
      rewriteRelativeUrl.foreach { rewrite =>
        document.accept(new AbstractVisitor {
          override def visit(link: Link): Unit = {
            Option(link.getDestination).foreach { url =>
              if (isRelativeUrl(url))
                link.setDestination(rewrite(url))
            }
            visitChildren(link)
          }
        })
      }
      renderer.render(document)
    }.getOrElse("Unable to parse markdown"))
  }
}
