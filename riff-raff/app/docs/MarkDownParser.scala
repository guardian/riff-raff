package docs

import com.gu.management.Loggable
import org.parboiled.common.StringUtils
import org.pegdown.FastEncoder._
import org.pegdown.LinkRenderer.Rendering
import org.pegdown.ast._
import org.pegdown.{Extensions, LinkRenderer, PegDownProcessor}
import play.api.mvc.Call
import play.twirl.api.Html

import scala.util.Try

object MarkDownParser extends Loggable {
  class CallLinkRenderer(urlToCall: String => Call) extends LinkRenderer {
    override def render(node: ExpLinkNode, text: String): Rendering = {
      val url = node.url
      val relative = !(url.matches("^[a-zA-Z]://") || url.matches("^/"))
      val modifiedUrl = if (relative) urlToCall(url).path else url

      val rendering = new LinkRenderer.Rendering(modifiedUrl, text)
      if (StringUtils.isEmpty(node.title)) rendering else rendering.withAttribute("title", encode(node.title))
    }
  }

  def toHtml(markDown: String, linkRenderer: Option[LinkRenderer] = None): Html = {
    Html(Try {
      val pegDown = new PegDownProcessor(Extensions.ALL - Extensions.HARDWRAPS - Extensions.ANCHORLINKS)
      linkRenderer match {
        case Some(renderer) =>
          logger.info(s"rendering some markdown... with custom renderer $renderer")
          pegDown.markdownToHtml(markDown, renderer)
        case None => pegDown.markdownToHtml(markDown)
      }
    }.getOrElse("Unable to parse markdown"))
  }
}