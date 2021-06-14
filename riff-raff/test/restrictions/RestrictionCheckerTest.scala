package restrictions

import java.util.UUID
import com.gu.googleauth.UserIdentity
import controllers.ApiKey
import deployment.{ApiRequestSource, ContinuousDeploymentRequestSource, Error, ScheduleRequestSource, UserRequestSource}
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RestrictionCheckerTest extends AnyFlatSpec with Matchers {

  val config = makeConfig("", "")
  val configs: Seq[RestrictionConfig] = Seq(
    makeConfig("testProject1", "PROD"),
    makeConfig("testProject1", "CODE", cd=true, whitelist = Seq("test.user@example.com")),
    makeConfig("testProject2", ".*")
  )

  val testRestrictions = new RestrictionsConfigRepository {
    def getRestrictions(projectName: String) = configs.filter(_.projectName == projectName)
    def getRestriction(id: UUID) = configs.find(_.id == id)
  }

  "editable" should "run the editable path when there is no existing config" in {
    RestrictionChecker.isEditable(
      None, makeUser("test.user@example.com"), Nil
    ) shouldBe Right(true)
  }

  it should "run the editable path when the config is not locked even if the user is different" in {
    val someConfig = Some(makeConfig("testProject", "PROD", creator = "test.creator@example.com", editingLocked = false))
    RestrictionChecker.isEditable(
      someConfig, makeUser("test.user@example.com"), Nil
    ) shouldBe Right(true)
  }

  it should "run the not editable path when the config is locked when the user is different" in {
    val someConfig = Some(makeConfig("testProject", "PROD", creator = "test.creator@example.com", editingLocked = true))
    RestrictionChecker.isEditable(
      someConfig, makeUser("test.user@example.com"), Nil
    ) shouldBe Left(Error("Locked by test.creator@example.com"))
  }

  it should "run the editable path when the config is locked and the user is the creator" in {
    val someConfig = Some(makeConfig("testProject", "PROD", creator = "test.creator@example.com", editingLocked = true))
    RestrictionChecker.isEditable(
      someConfig, makeUser("test.creator@example.com"), Nil
    ) shouldBe Right(true)
  }

  it should "allow modifications by super users" in {
    val someConfig = Some(makeConfig("testProject", "PROD", creator = "test.creator@example.com", editingLocked = true))
    RestrictionChecker.isEditable(
      someConfig, makeUser("superuser@example.com"), List("superuser@example.com")
    ) shouldBe Right(true)
  }

  it should "report superusers when locked" in {
    val someConfig = Some(makeConfig("testProject", "PROD", creator = "test.creator@example.com", editingLocked = true))
    RestrictionChecker.isEditable(
      someConfig, makeUser("test.user@example.com"), List("superuser@example.com")
    ) shouldBe Left(Error("Locked by test.creator@example.com - can also be modified by superuser@example.com"))
  }

  "configsThatPreventDeploy" should "return no configs when deploying to a project with no restrictions" in {
    val restrictions = RestrictionChecker.configsThatPreventDeployment(
      testRestrictions, "testProject0", "PROD", UserRequestSource(makeUser("test.user@example.com"))
    )
    restrictions.size shouldBe 0
  }

  it should "return the matching config for a prevented stage" in {
    val restrictions = RestrictionChecker.configsThatPreventDeployment(
        testRestrictions, "testProject1", "PROD", UserRequestSource(makeUser("test.user@example.com"))
    )
    restrictions.size shouldBe 1
    restrictions should contain(configs(0))
  }

  it should "return no configs for a stage that isn't prevented" in {
    val restrictions = RestrictionChecker.configsThatPreventDeployment(
      testRestrictions, "testProject1", "TEST", UserRequestSource(makeUser("test.user@example.com"))
    )
    restrictions.size shouldBe 0
  }

  it should "return the matching config for a prevented stage with a non-whitelisted user" in {
    val restrictions = RestrictionChecker.configsThatPreventDeployment(
      testRestrictions, "testProject1", "CODE", UserRequestSource(makeUser("bad.user@example.com"))
    )
    restrictions.size shouldBe 1
    restrictions should contain(configs(1))
  }

  it should "return no configs for a prevented stage with a whitelisted user" in {
    val restrictions = RestrictionChecker.configsThatPreventDeployment(
      testRestrictions, "testProject1", "CODE", UserRequestSource(makeUser("test.user@example.com"))
    )
    restrictions.size shouldBe 0
  }

  it should "return no configs for a permitted continuous deployment" in {
    val restrictions = RestrictionChecker.configsThatPreventDeployment(
      testRestrictions, "testProject1", "CODE", ContinuousDeploymentRequestSource
    )
    restrictions.size shouldBe 0
  }

  it should "return a config when the stage is a regex" in {
    val restrictions = RestrictionChecker.configsThatPreventDeployment(
      testRestrictions, "testProject2", "ANY", ContinuousDeploymentRequestSource
    )
    restrictions.size shouldBe 1
    restrictions should contain(configs(2))
  }

  "sourceMatches" should "match when a CD and CD is permitted" in {
    RestrictionChecker.sourceMatches(
      config.copy(continuousDeployment=true), ContinuousDeploymentRequestSource
    ) shouldBe true
  }

  it should "not match when a CD and CD is not permitted" in {
    RestrictionChecker.sourceMatches(
      config.copy(continuousDeployment=false), ContinuousDeploymentRequestSource
    ) shouldBe false
  }

  it should "match when a scheduled deploy and CD is permitted" in {
    RestrictionChecker.sourceMatches(
      config.copy(continuousDeployment=true), ScheduleRequestSource
    ) shouldBe true
  }

  it should "not match when a scheduled deploy and CD is not permitted" in {
    RestrictionChecker.sourceMatches(
      config.copy(continuousDeployment=false), ScheduleRequestSource
    ) shouldBe false
  }

  it should "not match when an API source" in {
    RestrictionChecker.sourceMatches(
      config, ApiRequestSource(ApiKey("", "", "", new DateTime()))
    ) shouldBe false
  }

  it should "match when a user request and user is present in whitelist" in {
    RestrictionChecker.sourceMatches(
      config.copy(whitelist = Seq("user1@example.com", "test.user@example.com", "user3@example.com")),
      UserRequestSource(makeUser("test.user@example.com"))
    ) shouldBe true
  }

  it should "not match when a user request and user is not present in whitelist" in {
    RestrictionChecker.sourceMatches(
      config.copy(whitelist = Seq("user1@example.com", "user3@example.com")),
      UserRequestSource(makeUser("test.user@example.com"))
    ) shouldBe false
  }

  "stageMatches" should "match a literal string" in {
    RestrictionChecker.stageMatches("CODE", "CODE") shouldBe true
  }

  it should "not match different literals" in {
    RestrictionChecker.stageMatches("PROD", "CODE") shouldBe false
  }

  it should "match a regular expression" in {
    RestrictionChecker.stageMatches(".*", "CODE") shouldBe true
  }

  it should "match a prefix regular expression" in {
    RestrictionChecker.stageMatches("PROD.*", "PROD") shouldBe true
    RestrictionChecker.stageMatches("PROD.*", "PROD-ZEBRA") shouldBe true
  }

  it should "not match a mis-matched prefix regular expression" in {
    RestrictionChecker.stageMatches("PROD.*", "CODE") shouldBe false
    RestrictionChecker.stageMatches("PROD.*", "CODE-ZEBRA") shouldBe false
    RestrictionChecker.stageMatches("PROD.*", "ZEBRA-PROD") shouldBe false
  }

  def makeConfig(projectName: String, stage: String, whitelist: Seq[String] = Seq.empty, cd: Boolean = false,
    creator:String = "", editingLocked: Boolean = false) =
  RestrictionConfig(UUID.randomUUID, projectName, stage, new DateTime(), creator, creator, editingLocked, whitelist, cd, "")
  def makeUser(email: String) = UserIdentity("1234", email, "Test", "User", 123344567845L, None)

}
