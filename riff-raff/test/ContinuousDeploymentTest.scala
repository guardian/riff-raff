package test

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import com.gu.conf.Configuration
import teamcity.GuContinuousDeploymentConfig
import deployment.Domains

class ContinuousDeploymentTest extends FlatSpec with ShouldMatchers with DomainsTestHelper {

  def createTestConfig(properties: Map[String,String]): GuContinuousDeploymentConfig = {
    lazy val guConfig = new Configuration {
      def getPropertyNames = properties.keySet
      def getStringProperty(propertyName: String) = properties.get(propertyName)
      def getIdentifier = "test"
    }
    GuContinuousDeploymentConfig(guConfig, new Domains(testDomainsConfiguration(properties)))
  }

  "continuous deployment config" should "correctly parse the enabled flag" in {
    val configTrue = createTestConfig(Map("continuous.deployment" -> "", "continuous.deployment.enabled" -> "true"))
    configTrue.enabled should be(true)
    val configFalse = createTestConfig(Map("continuous.deployment" -> "", "continuous.deployment.enabled" -> "false"))
    configFalse.enabled should be(false)
  }

  it should "parse one app and one stage" in {
    val config = createTestConfig(Map("continuous.deployment" -> "frontend::article->CODE"))
    config.buildToStageMap should be(Map("frontend::article" -> Set("CODE")))
  }

  it should "parse multiple apps and stages" in {
    val config = createTestConfig(Map("continuous.deployment" -> "frontend::article->CODE,PROD|frontend::front->CODE,TEST,PROD"))
    config.buildToStageMap should be(
      Map(
        "frontend::article" -> Set("CODE", "PROD"),
        "frontend::front" -> Set("CODE", "PROD", "TEST")
      )
    )
  }

  it should "parse apps containing spaces" in {
    val config = createTestConfig(Map("continuous.deployment" -> "Content Stream::content-api->CODE"))
    config.buildToStageMap should be(
      Map(
        "Content Stream::content-api" -> Set("CODE")
      )
    )
  }

  it should "filter stages according to the domains configuration" in {
    val config = createTestConfig(Map(
      "continuous.deployment" -> "frontend::article->CODE,PROD|frontend::front->CODE,TEST,PROD",
      "domains.enabled" -> "true",
      "domains.identity" -> "test2",
      "domains.test1.responsibility.stage.regex" -> "^PROD$",
      "domains.test1.urlPrefix" -> "https://test1",
      "domains.test2.responsibility.stage.regex" -> "^PROD$",
      "domains.test2.responsibility.stage.invertRegex" -> "true",
      "domains.test2.urlPrefix" -> "https://test2"
    ))
    config.buildToStageMap should be(
      Map(
        "frontend::article" -> Set("CODE"),
        "frontend::front" -> Set("CODE", "TEST")
      )
    )
  }
}
