package deployment

import magenta.{Stage, DeployParameters}
import conf.Configuration
import util.matching.Regex
import controllers.Logging
import com.gu.conf.{Configuration => GuConfiguration}

object Shard {
  lazy val matchAll = Shard("matchAll", "matchAll", """^.*$""", invertRegex = false)
  lazy val matchNone = Shard("matchNone", "matchNone", """^.*$""", invertRegex = true)
}

case class Shard(name: String, urlPrefix: String, regexString: String, invertRegex: Boolean) {
  lazy val regex = regexString.r
  def matchStage(stage:Stage): Boolean = {
    val matchRegex = regex.pattern.matcher(stage.name).matches()
    (matchRegex || invertRegex) && !(matchRegex && invertRegex) // XOR
  }
}

trait ShardingConfiguration {
  def enabled: Boolean
  def identity: Shard
  def shards: Iterable[Shard]
}

case class GuShardingConfiguration(configuration: GuConfiguration, prefix: String) extends ShardingConfiguration with Logging {

  lazy val enabled = configuration.getStringProperty("%s.enabled" format prefix, "false") == "true"
  lazy val identityName = configuration.getStringProperty("%s.identity" format prefix, java.net.InetAddress.getLocalHost.getHostName)
  lazy val nodes = findNodeNames
  lazy val shards = nodes.map( parseNode(_) )

  def findNodeNames = configuration.getPropertyNames.filter(_.startsWith("%s." format prefix)).flatMap{ property =>
    val elements = property.split('.')
    if (elements.size > 2) Some(elements(1)) else None
  }

  def parseNode(nodeName: String): Shard = {
    val regex = configuration.getStringProperty("%s.%s.responsibility.stage.regex" format (prefix, nodeName), "^$")
    val invertRegex = configuration.getStringProperty("%s.%s.responsibility.stage.invertRegex" format (prefix, nodeName), "false") == "true"
    val urlPrefix = configuration.getStringProperty("%s.%s.urlPrefix" format (prefix, nodeName), nodeName)
    Shard(nodeName, urlPrefix, regex, invertRegex)
  }

  lazy val identity:Shard =
    if (!enabled)
      Shard.matchAll
    else {
      val candidates = shards.filter(_.name == identityName)
      candidates.size match {
        case 0 =>
          log.warn("No shard configuration for this node (%s)" format identityName)
          Shard.matchNone
        case 1 =>
          candidates.head
        case _ =>
          throw new IllegalStateException("Multiple shard configurations match this node (%s): %s" format (identityName, candidates.mkString(",")))
      }
    }
}

class Sharding(conf: ShardingConfiguration) extends Logging {
  import Sharding._

  def assertResponsibleFor(params: DeployParameters) {
    if (!conf.identity.matchStage(params.stage))
      throw new IllegalArgumentException("This riffraff node can't handle deploys for stage %s" format params.stage)
  }

  def responsibleFor(params: DeployParameters): Action = {
    if (conf.identity.matchStage(params.stage))
      Local()
    else {
      val matches = conf.shards.filter(_.matchStage(params.stage))
      matches.size match {
        case 0 => throw new IllegalStateException("No shard found to handle stage %s" format params.stage)
        case n:Int =>
          if (n>1) log.warn("Multiple shards match for stage ")
          Remote(matches.head.urlPrefix)
      }
    }
  }
}

object Sharding extends Sharding(Configuration.sharding) {
  trait Action
  case class Local() extends Action
  case class Remote(urlPrefix: String) extends Action
}

