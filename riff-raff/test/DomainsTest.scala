package test

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import com.gu.conf.Configuration
import deployment.{Domain, Domains, GuDomainsConfiguration, DomainAction}
import magenta.{Stage, Deployer, Build, DeployParameters}

trait DomainsTestHelper {
  def testDomainsConfiguration(properties: Map[String, String], prefix: Option[String] = None): GuDomainsConfiguration = {
    lazy val prefixedProperties = prefix.map(prefix => properties.map(e => ("%s.%s" format(prefix, e._1), e._2))).getOrElse(properties)
    lazy val guConfig = new Configuration {
      def getPropertyNames = prefixedProperties.keySet
      def getStringProperty(propertyName: String) = prefixedProperties.get(propertyName)
      def getIdentifier = "test"
    }
    GuDomainsConfiguration(guConfig, prefix.getOrElse("domains"))
  }
}

class DomainsTest extends FlatSpec with ShouldMatchers with DomainsTestHelper {
  val basicConfigProperties = Map(
    "domains.enabled" -> "true",
    "domains.identity" -> "test1",
    "domains.test1.responsibility.stage.regex" -> "^PROD$",
    "domains.test1.urlPrefix" -> "https://test1",
    "domains.test2.responsibility.stage.regex" -> "^PROD$",
    "domains.test2.responsibility.stage.invertRegex" -> "true",
    "domains.test2.urlPrefix" -> "https://test2"
  )


  "domains config" should "gather node names" in {
    val config = testDomainsConfiguration(basicConfigProperties)
    config.findNodeNames should be(Set("test1", "test2"))
  }

  it should "parse a configuration" in {
    val config = testDomainsConfiguration(basicConfigProperties)
    config.enabled should be(true)
    config.domains should be(Set(
      Domain("test2", "https://test2", "^PROD$", true),
      Domain("test1", "https://test1", "^PROD$", false)
    ))
  }

  it should "use matchAll when disabled" in {
    val disabledMap = Map("domains.enabled" -> "false")
    val config = testDomainsConfiguration(disabledMap)
    config.enabled should be(false)
    config.identity should be(Domain.matchAll)
  }

  it should "use matchNone when enabled without an identity" in {
    val map = basicConfigProperties - "domains.identity"
    val config = testDomainsConfiguration(map)
    config.enabled should be(true)
    config.identity should be(Domain.matchNone)
  }

  "domains" should "use local action if responsible for stage" in {
    val config = testDomainsConfiguration(basicConfigProperties)
    val domains = new Domains(config)
    val action = domains.responsibleFor(DeployParameters(Deployer("test"), Build("test", "test"), Stage("PROD")))
    action should be(DomainAction.Local())
  }

  it should "use remote action if not responsible for stage" in {
    val config = testDomainsConfiguration(basicConfigProperties)
    val domains = new Domains(config)
    val action = domains.responsibleFor(DeployParameters(Deployer("test"), Build("test", "test"), Stage("TEST")))
    action should be(DomainAction.Remote("https://test2"))
  }
}