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
          var styles = ""

          if (item.current) styles += "active "
          if (!item.kids.isEmpty) styles += "dropdown "

          <li class={styles}><a href={item.uri}>{item.text}</a></li>
        }
      }
    </ul>

  }
}