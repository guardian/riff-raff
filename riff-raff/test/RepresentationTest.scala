package test

import java.util.UUID
import controllers.ApiKey
import magenta.Strategy.MostlyHarmless
import magenta._
import org.joda.time.DateTime
import org.scalatest.{FlatSpec, Matchers}
import persistence._

class RepresentationTest extends FlatSpec with Matchers with Utilities with PersistenceTestInstances {

  "MessageDocument" should "convert from log messages to documents" in {
    deploy.asMessageDocument should be(DeployDocument)
    infoMsg.asMessageDocument should be(InfoDocument("$ echo hello"))
    cmdOut.asMessageDocument should be(CommandOutputDocument("hello"))
    verbose.asMessageDocument should be(VerboseDocument("return value 0"))
    finishDep.asMessageDocument should be(FinishContextDocument)
    finishInfo.asMessageDocument should be(FinishContextDocument)
    failInfo.asMessageDocument should be(FailContextDocument)
    failDep.asMessageDocument should be(FailContextDocument)
    warning.asMessageDocument should be(WarningDocument("deprecation"))
  }

  it should "not convert StartContext log messages" in {
    intercept[IllegalArgumentException]{
      startDeploy.asMessageDocument
    }
  }

  "DeployRecordDocument" should "build from a deploy record" in {
    testDocument should be(
      DeployRecordDocument(
        testUUID,
        Some(testUUID.toString),
        testTime,
        ParametersDocument("Tester", "test-project", "1", "CODE", Map("branch"->"master"), AllDocument, updateStrategy = MostlyHarmless),
        RunState.Completed
      )
    )
  }

  "Rich list" should "retain the order of a list" in {
    case class Monkey(name: String, age: Int)
    val monkies = List(Monkey("fred", 1), Monkey("bob", 2), Monkey("marjorie", 3))
    val distinctMonkies = monkies.distinctOn(identity)
    distinctMonkies shouldBe monkies
  }

  it should "remove duplicates later in the list" in {
    case class Monkey(name: String, age: Int)
    val monkies = List(Monkey("fred", 1), Monkey("bob", 2), Monkey("marjorie", 3), Monkey("bob", 3))
    val distinctMonkies = monkies.distinctOn(_.name)
    distinctMonkies shouldBe List(Monkey("fred", 1), Monkey("marjorie", 3), Monkey("bob", 3))
  }

}
