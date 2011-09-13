package riff.raff.snippet

import net.liftweb.http._
import xml.NodeSeq
import net.liftweb.sitemap.MenuItem


class BootstrapMenu {
  def render(in: NodeSeq): NodeSeq = {
    val menuEntries =
      (for {sm <- LiftRules.siteMap; req <- S.request} yield sm.buildMenu(req.location).lines) openOr Nil

    <ul class="nav">
      {
        for (item <- menuEntries) yield {
          var styles = item.cssClass openOr ""

          if (item.current) styles += " active"

          item.kids match {
            case Nil =>
              <li class={styles}><a href={item.uri}>{item.text}</a></li>

            case kids =>
              <li class={styles + " dropdown"}>
                <a href="#" class="dropdown-toggle">{item.text}</a>
                <ul class="dropdown-menu">
                  {
                    for (kid <- kids) yield {
                      <li><a href={kid.uri}>{kid.text}</a></li>
                    }
                  }
                </ul>
              </li>
          }
        }
      }
    </ul>

  }

}