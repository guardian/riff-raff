package deployment

import magenta.{Stage, DeployParameters}
import conf.Configuration
import util.matching.Regex
import controllers.{AuthAction, Logging}
import play.api.mvc.Results._

object Shard {
  lazy val matchAll = Shard("matchAll", "matchAll", """^.*$""".r, invertRegex = false)
}

case class Shard(name: String, urlPrefix: String, regex: Regex, invertRegex: Boolean) {
  def matchStage(stage:Stage): Boolean = {
    val matchRegex = regex.pattern.matcher(stage.name).matches()
    (matchRegex || invertRegex) && !(matchRegex && invertRegex) // XOR
  }
}

trait ShardedAction
case class LocalAction() extends ShardedAction
case class RemoteAction(urlPrefix: String) extends ShardedAction

object Sharding extends Logging {
  val shards = Configuration.sharding.shards

  val thisShard:Shard = {
    if (Configuration.sharding.enabled) {
      val shards = Configuration.sharding.shards
      val candidates = shards.filter(_.name == Configuration.sharding.identity)
      candidates.size match {
        case 0 =>
          log.warn("No shard configuration for this node (%s)" format Configuration.sharding.identity)
          None
        case 1 =>
          Some(candidates.head)
        case _ =>
          throw new IllegalStateException("Multiple shard configurations match this node (%s): %s" format (Configuration.sharding.identity, candidates.mkString(",")))
      }
    } else {
      None
    }
  } getOrElse( Shard.matchAll )

  def assertResponsibleFor(params: DeployParameters) {
    if (!thisShard.matchStage(params.stage)) throw new IllegalArgumentException("This riffraff node can't handle deploys for stage %s" format params.stage)
  }

  def responsibleFor(params: DeployParameters): ShardedAction = {
    if (thisShard.matchStage(params.stage))
      LocalAction()
    else {
      val matches = shards.filter(_.matchStage(params.stage))
      matches.size match {
        case 0 => throw new IllegalStateException("No shard found to handle stage %s" format params.stage)
        case n:Int =>
          if (n>1) log.warn("Multiple shards match for stage ")
          RemoteAction(matches.head.urlPrefix)
      }
    }
  }
}

