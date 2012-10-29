package test

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import com.gu.conf.Configuration
import deployment.{Shard, Sharding, GuShardingConfiguration}
import magenta.{Stage, Deployer, Build, DeployParameters}

class ShardingTest extends FlatSpec with ShouldMatchers {

  def testShardConfiguration(properties: Map[String,String], prefix: Option[String] = None): GuShardingConfiguration = {
    lazy val prefixedProperties = prefix.map(prefix => properties.map(e => ("%s.%s" format (prefix, e._1), e._2))).getOrElse(properties)
    lazy val guConfig = new Configuration {
      def getPropertyNames = prefixedProperties.keySet
      def getStringProperty(propertyName: String) = prefixedProperties.get(propertyName)
      def getIdentifier = "test"
    }
    GuShardingConfiguration(guConfig, prefix.getOrElse("sharding"))
  }

  val basicConfigProperties = Map(
    "sharding.enabled" -> "true",
    "sharding.identity" -> "test1",
    "sharding.test1.responsibility.stage.regex" -> "^PROD$",
    "sharding.test1.urlPrefix" -> "https://test1",
    "sharding.test2.responsibility.stage.regex" -> "^PROD$",
    "sharding.test2.responsibility.stage.invertRegex" -> "true",
    "sharding.test2.urlPrefix" -> "https://test2"
  )


  "sharding config" should "gather node names" in {
    val config = testShardConfiguration(basicConfigProperties)
    config.findNodeNames should be(Set("test1", "test2"))
  }

  it should "parse a configuration" in {
    val config = testShardConfiguration(basicConfigProperties)
    config.enabled should be(true)
    config.shards should be(Set(
      Shard("test2", "https://test2", "^PROD$", true),
      Shard("test1", "https://test1", "^PROD$", false)
    ))
  }

  it should "use matchAll when disabled" in {
    val disabledMap = Map("sharding.enabled" -> "false")
    val config = testShardConfiguration(disabledMap)
    config.enabled should be(false)
    config.identity should be(Shard.matchAll)
  }

  it should "use matchNone when enabled without an identity" in {
    val map = basicConfigProperties - "sharding.identity"
    val config = testShardConfiguration(map)
    config.enabled should be(true)
    config.identity should be(Shard.matchNone)
  }

  "sharding" should "use local action if responsible for stage" in {
    val config = testShardConfiguration(basicConfigProperties)
    val shard = new Sharding(config)
    val action = shard.responsibleFor(DeployParameters(Deployer("test"), Build("test", "test"), Stage("PROD")))
    action should be(Sharding.Local())
  }

  it should "use remote action if not responsible for stage" in {
    import Sharding._
    val config = testShardConfiguration(basicConfigProperties)
    val shard = new Sharding(config)
    val action = shard.responsibleFor(DeployParameters(Deployer("test"), Build("test", "test"), Stage("TEST")))
    action should be(Remote("https://test2"))
  }
}