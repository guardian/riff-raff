package ci

import org.apache.pekko.actor.ActorSystem

object Context {
  val actorSystem = ActorSystem("build-agents")
  implicit val executionContext = actorSystem.dispatcher
}
