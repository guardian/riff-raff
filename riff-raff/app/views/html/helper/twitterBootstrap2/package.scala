package views.html.helper

import views.html.html.helper.twitterBootstrap2.twitterBootstrap2FieldConstructor

package object twitterBootstrap2 {

  implicit val twitterBootstrap2Field = new FieldConstructor {
    def apply(elts: FieldElements) = twitterBootstrap2FieldConstructor(elts)
  }

}