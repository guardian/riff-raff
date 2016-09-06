package controllers

import play.api.i18n.MessagesApi

/**
  * TODO delete me once we have DI
  */
trait MessagesHack {

  lazy val messagesApi: MessagesApi = play.api.Play.current.injector.instanceOf[MessagesApi]

}
