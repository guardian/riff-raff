package bootstrap.liftweb

import net.liftweb.sitemap._
import net.liftweb.http._
import net.liftweb.common._
import net.liftweb.sitemap.Loc._
import riff.raff.model.AdminUser
import riff.raff.Config
import net.liftweb.sitemap.LocPath._


class Boot {
  val MustBeLoggedIn = If(() => AdminUser.isLoggedIn, () => RedirectResponse("/login"))
  val MustNotBeLoggedIn = If(() => !AdminUser.isLoggedIn, "You can't access this page if you're logged in")

  val menus = List(
     Menu("Home") / "index" >> MustBeLoggedIn ,
     Menu("Stage") / "stage" >> MustBeLoggedIn submenus (
       for (stage <- Config.stages) yield { Menu(stage) / "stage" / stage }
     ),
     Menu("Login") / "login" >> MustNotBeLoggedIn >> MenuCssClass("secondary-nav"),
     Menu("Logout") / "logout" >> MustBeLoggedIn >> MenuCssClass("secondary-nav")
  )

  def boot() {

    LiftRules.htmlProperties.default.set((r: Req) => new Html5Properties(r.userAgent))

    LiftRules.loggedInTest = Full(() => AdminUser.isLoggedIn)

    LiftRules.addToPackages("riff.raff")

    LiftRules.setSiteMap(SiteMap(menus : _*))

    LiftRules.dispatch.append {
      case Req("logout" :: Nil, _, _) => () => { AdminUser.logout(); Full(RedirectResponse("/")) }
    }

    LiftRules.statefulRewrite.append {
      case RewriteRequest(ParsePath("stage" :: stg :: Nil, "", true, _), _, _) =>
        RewriteResponse("stage" :: Nil, Map("stage" -> stg))
    }
  }

}