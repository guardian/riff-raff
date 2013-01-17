package deployment

import magenta.{Stage, DeployParameters}
import conf.Configuration
import controllers.Logging
import com.gu.conf.{Configuration => GuConfiguration}

object Domain {
  lazy val matchAll = Domain("matchAll", "matchAll", """^.*$""", invertRegex = false)
  lazy val matchNone = Domain("matchNone", "matchNone", """^.*$""", invertRegex = true)
}

case class Domain(name: String, urlPrefix: String, regexString: String, invertRegex: Boolean) {
  lazy val regex = regexString.r
  def matchStage(stage:Stage): Boolean = {
    val matchRegex = regex.pattern.matcher(stage.name).matches()
    (matchRegex || invertRegex) && !(matchRegex && invertRegex) // XOR
  }
}

trait DomainsConfiguration {
  def enabled: Boolean
  def identity: Domain
  def domains: Iterable[Domain]
}

case class GuDomainsConfiguration(configuration: GuConfiguration, prefix: String) extends DomainsConfiguration with Logging {

  lazy val enabled = configuration.getStringProperty("%s.enabled" format prefix, "false") == "true"
  lazy val identityName = configuration.getStringProperty("%s.identity" format prefix, java.net.InetAddress.getLocalHost.getHostName)
  lazy val nodes = findNodeNames
  lazy val domains = nodes.map( parseNode(_) )

  def findNodeNames = configuration.getPropertyNames.filter(_.startsWith("%s." format prefix)).flatMap{ property =>
    val elements = property.split('.')
    if (elements.size > 2) Some(elements(1)) else None
  }

  def parseNode(nodeName: String): Domain = {
    val regex = configuration.getStringProperty("%s.%s.responsibility.stage.regex" format (prefix, nodeName), "^$")
    val invertRegex = configuration.getStringProperty("%s.%s.responsibility.stage.invertRegex" format (prefix, nodeName), "false") == "true"
    val urlPrefix = configuration.getStringProperty("%s.%s.urlPrefix" format (prefix, nodeName), nodeName)
    Domain(nodeName, urlPrefix, regex, invertRegex)
  }

  lazy val identity:Domain =
    if (!enabled)
      Domain.matchAll
    else {
      val candidates = domains.filter(_.name == identityName)
      candidates.size match {
        case 0 =>
          log.warn("No domain configuration for this node (%s)" format identityName)
          Domain.matchNone
        case 1 =>
          candidates.head
        case _ =>
          throw new IllegalStateException("Multiple domain configurations match this node (%s): %s" format (identityName, candidates.mkString(",")))
      }
    }
}

object DomainAction {
  trait Action
  case class Local() extends Action
  case class Remote(name: String, urlPrefix: String) extends Action
  case class Noop() extends Action
}

trait DomainResponsibility extends Logging {
  import DomainAction._
  def conf: DomainsConfiguration

  def assertResponsibleFor(params: DeployParameters) {
    if (!conf.identity.matchStage(params.stage))
      throw new IllegalArgumentException("This riffraff node can't handle deploys for stage %s" format params.stage)
  }

  def responsibleFor(params: DeployParameters): Action = {
    if (conf.identity.matchStage(params.stage))
      Local()
    else {
      val matches = conf.domains.filter(_.matchStage(params.stage))
      matches.size match {
        case 0 =>
          log.warn("No domain found to handle stage %s" format params.stage)
          Noop()
        case n:Int =>
          if (n>1) log.warn("Multiple domains match for stage ")
          Remote(matches.head.name, matches.head.urlPrefix)
      }
    }
  }
}

class Domains(val conf: DomainsConfiguration) extends DomainResponsibility

object Domains extends Domains(Configuration.domains)

