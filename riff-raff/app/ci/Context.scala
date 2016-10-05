package ci

import akka.actor.ActorSystem

object Context {
  val actorSystem = ActorSystem("build-agents")
  implicit val executionContext = actorSystem.dispatcher
}
