package test

import java.util.UUID

import com.mongodb.DBObject
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.commons.conversions.scala._
import com.mongodb.util.JSON
import controllers.ApiKey
import magenta._
import org.bson.{BasicBSONDecoder, BasicBSONEncoder}
import org.joda.time.DateTime
import org.scalatest.{FlatSpec, Matchers}
import persistence._

class RepresentationTest extends FlatSpec with Matchers with Utilities with PersistenceTestInstances {

  RegisterJodaTimeConversionHelpers()

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

  "LogDocument" should "serialise all message types to BSON" in {
    val messages = Seq(deploy, infoMsg, cmdOut, verbose, finishDep, finishInfo, failInfo, failDep)
    val documents = messages.map(LogDocument(testUUID, UUID.randomUUID(), Some(UUID.randomUUID()), _, testTime))
    documents.foreach { document =>
      val dbObject = document.toDBO
      dbObject should not be null
      val encoder = new BasicBSONEncoder()
      val bytes = encoder.encode(dbObject)
      bytes should not be null
    }
  }

  it should "not change without careful thought and testing of migration" in {
    val time = new DateTime(2012,11,8,17,20,0)
    val id = UUID.fromString("4ef18506-3b38-4235-9933-d7da831247a6")
    val parentId = UUID.fromString("4236c133-be50-4169-8e0c-096eded5bfeb")
    val messageJsonMap = Map(
      deploy -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.DeployDocument$"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      infoMsg -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.InfoDocument$" , "text" : "$ echo hello"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      cmdOut -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.CommandOutputDocument$" , "text" : "hello"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      verbose -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.VerboseDocument$" , "text" : "return value 0"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      finishDep -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.FinishContextDocument$"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      finishInfo -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.FinishContextDocument$"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      failInfo -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.FailContextDocument$"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      failDep -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.FailContextDocument$"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      warning -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.WarningDocument$" , "text" : "deprecation"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}"""
    )
    messageJsonMap.foreach { case (message, json) =>
      val logDocument = LogDocument(testUUID, id, Some(parentId), message, time)

      val gratedDocument = logDocument.toDBO
      val jsonLogDocument = JSON.serialize(gratedDocument)

      val diff = compareJson(json, jsonLogDocument)

      if (json.isEmpty) {
        jsonLogDocument should be(json)
      } else {
        diff.toString should be("[ ]")
        // TODO - check ordering as well?
        //jsonLogDocument should be(json)
      }

      val ungratedDBObject = JSON.parse(json).asInstanceOf[DBObject]
      ungratedDBObject.toString should be(json)

      val ungratedDeployDocument = LogDocument.fromDBO(new MongoDBObject(ungratedDBObject))
      ungratedDeployDocument should be(Some(logDocument))
    }
  }

  "DeployRecordDocument" should "build from a deploy record" in {
    testDocument should be(
      DeployRecordDocument(
        testUUID,
        Some(testUUID.toString),
        testTime,
        ParametersDocument("Tester", "test-project", "1", "CODE", Map("branch"->"master"), AllDocument),
        RunState.Completed
      )
    )
  }

  it should "serialise to BSON" in {
    val dbObject = testDocument.toDBO
    dbObject should not be null
    val encoder = new BasicBSONEncoder()
    val bytes = encoder.encode(dbObject)
    bytes should not be null
  }

  it should "never change without careful thought and testing of migration" in {
    val dataModelDump = """{ "_id" : { "$uuid" : "39320f5b-7837-4f47-85f7-bc2d780e19f6"} , "stringUUID" : "39320f5b-7837-4f47-85f7-bc2d780e19f6" , "startTime" : { "$date" : "2012-11-08T17:20:00.000Z"} , "parameters" : { "deployer" : "Tester" , "projectName" : "test::project" , "buildId" : "1" , "stage" : "TEST" , "tags" : { "branch" : "test"} , "selector" : { "_typeHint" : "persistence.AllDocument$"}} , "status" : "Completed"}"""

    val deployDocument = RecordConverter(comprehensiveDeployRecord).deployDocument
    val gratedDeployDocument = deployDocument.toDBO

    val jsonDeployDocument = JSON.serialize(gratedDeployDocument)
    val diff = compareJson(dataModelDump, jsonDeployDocument)
    diff.toString should be("[ ]")
    // TODO - check ordering as well?
    //jsonDeployDocument should be(dataModelDump)

    val ungratedDBObject = JSON.parse(dataModelDump).asInstanceOf[DBObject]
    ungratedDBObject.toString should be(dataModelDump)

    val ungratedDeployDocument = DeployRecordDocument.fromDBO(new MongoDBObject(ungratedDBObject))
    ungratedDeployDocument should be(Some(deployDocument))
  }

  "ApiKey" should "serialise to and from BSON" in {
    val time = new DateTime(2012,11,8,17,20,0)
    val lastTime = new DateTime(2013,1,8,17,20,0)
    val apiKey = ApiKey("test-application", "hfeklwb34uiopfnu34io2tr_-fffDS", "Test User", time, Some(lastTime), Map("counter1" -> 34L, "counter2" -> 2345L))

    val dbObject = apiKey.toDBO
    val encoder = new BasicBSONEncoder()
    val bytes = encoder.encode(dbObject)

    bytes should not be null

    val decoder = new BasicBSONDecoder()
    val decoded = decoder.readObject(bytes)
    decoded should be(dbObject)
  }

  it should "never change without careful thought and testing of migration" in {
    val time = new DateTime(2012,11,8,17,20,0)
    val lastTime = new DateTime(2013,1,8,17,20,0)

    val apiKeyDump = """{ "application" : "test-application" , "_id" : "hfeklwb34uiopfnu34io2tr_-fffDS" , "issuedBy" : "Test User" , "created" : { "$date" : "2012-11-08T17:20:00.000Z"} , "lastUsed" : { "$date" : "2013-01-08T17:20:00.000Z"} , "callCounters" : { "counter1" : 34 , "counter2" : 2345}}"""

    val apiKey = ApiKey("test-application", "hfeklwb34uiopfnu34io2tr_-fffDS", "Test User", time, Some(lastTime), Map("counter1" -> 34L, "counter2" -> 2345L))
    val gratedApiKey = apiKey.toDBO

    val jsonApiKey = JSON.serialize(gratedApiKey)
    val diff = compareJson(apiKeyDump, jsonApiKey)
    diff.toString should be("[ ]")
    // TODO - check ordering as well?
    //jsonApiKey should be(apiKeyDump)

    val ungratedDBObject = JSON.parse(apiKeyDump).asInstanceOf[DBObject]
    ungratedDBObject.toString should be(apiKeyDump)

    val ungratedApiKey = ApiKey.fromDBO(new MongoDBObject(ungratedDBObject))
    ungratedApiKey should be(Some(apiKey))
  }

  "DeploymentSelectorDocument" should "not change AllDocument without careful thought and testing of migration" in {
    val allDump = """{ "_typeHint" : "persistence.AllDocument$"}"""
    val allDbo = AllDocument.asDBObject
    val allJson = JSON.serialize(allDbo)
    val allDiff = compareJson(allDump, allJson)
    allDiff.toString shouldBe "[ ]"
    val ungratedDBOject = JSON.parse(allJson).asInstanceOf[DBObject]
    ungratedDBOject.toString shouldBe allDump

    val ungratedAll = DeploymentSelectorDocument.from(ungratedDBOject)
    ungratedAll shouldBe AllDocument
  }

  it should "not change DeployIdsFilterDocument without careful thought and testing of migration" in {
    // deploy IDs filter
    val deployKeysDump = """{ "_typeHint" : "persistence.DeploymentKeysSelectorDocument" , "keys" : [ { "name" : "testName" , "action" : "testAction" , "stack" : "testStack" , "region" : "testRegion"}]}"""
    val keysSelectorDocument = DeploymentKeysSelectorDocument(List(DeploymentKeyDocument("testName", "testAction", "testStack", "testRegion")))
    val keysSelectorDbo = keysSelectorDocument.asDBObject
    val keySelectorJson = JSON.serialize(keysSelectorDbo)
    val keysSelectorDiff = compareJson(deployKeysDump, keySelectorJson)
    keysSelectorDiff.toString shouldBe "[ ]"
    val ungratedDeployIdsDBOject = JSON.parse(keySelectorJson).asInstanceOf[DBObject]
    ungratedDeployIdsDBOject.toString shouldBe deployKeysDump

    val ungratedDeployKeys = DeploymentSelectorDocument.from(ungratedDeployIdsDBOject)
    ungratedDeployKeys shouldBe keysSelectorDocument
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
