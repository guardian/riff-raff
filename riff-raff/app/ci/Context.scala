package ci

import akka.actor.ActorSystem

object Context {
  val actorSystem = ActorSystem("teamcity-trackers")
  implicit val executionContext = actorSystem.dispatcher
}