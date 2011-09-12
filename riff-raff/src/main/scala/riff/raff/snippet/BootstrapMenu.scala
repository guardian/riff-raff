package riff.raff.snippet

import net.liftweb.http._
import xml.NodeSeq
import net.liftweb.sitemap.MenuItem


class BootstrapMenu {
  def render(in: NodeSeq): NodeSeq = {
    val menuEntries = S.request.map(_.buildMenu.lines) openOr Nil

    <ul class="nav">
      {
        for (item <- menuEntries) yield {
          item match {
            // "current" menu item
            case MenuItem(text, uri, _, true, _, _) =>
              <li class="active"><a href={uri}>{text}</a></li>

            // non-current top level menu item
            case MenuItem(text, uri, _, false, _, _) =>
              <li><a href={uri}>{text}</a></li>

            case other =>
              NodeSeq.Empty

          }
        }
      }
    </ul>
  }

}