package test

import ci.ContinuousDeploymentConfig
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import persistence._
import org.bson.BasicBSONEncoder
import org.joda.time.DateTime
import com.mongodb.util.JSON
import com.mongodb.DBObject
import com.mongodb.casbah.commons.MongoDBObject
import magenta._
import java.util.UUID
import controllers.ApiKey


class RepresentationTest extends FlatSpec with ShouldMatchers with Utilities with PersistenceTestInstances {

  "MessageDocument" should "convert from log messages to documents" in {
    deploy.asMessageDocument should be(DeployDocument())
    infoMsg.asMessageDocument should be(InfoDocument("$ echo hello"))
    cmdOut.asMessageDocument should be(CommandOutputDocument("hello"))
    verbose.asMessageDocument should be(VerboseDocument("return value 0"))
    finishDep.asMessageDocument should be(FinishContextDocument())
    finishInfo.asMessageDocument should be(FinishContextDocument())
    failInfo.asMessageDocument should be(FailContextDocument())
    failDep.asMessageDocument should be(FailContextDocument())
  }

  it should "not convert StartContext log messages" in {
    intercept[IllegalArgumentException]{
      startDeploy.asMessageDocument
    }
  }

  "LogDocument" should "serialise all message types to BSON" in {
    val messages = Seq(deploy, infoMsg, cmdOut, verbose, finishDep, finishInfo, failInfo, failDep)
    val documents = messages.map(LogDocument(testUUID, UUID.randomUUID(), Some(UUID.randomUUID()), _, testTime))
    documents.foreach{ document =>
      val dbObject = graters.logDocumentGrater.asDBObject(document)
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
      deploy -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.DeployDocument"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      infoMsg -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.InfoDocument" , "text" : "$ echo hello"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      cmdOut -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.CommandOutputDocument" , "text" : "hello"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      verbose -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.VerboseDocument" , "text" : "return value 0"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      finishDep -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.FinishContextDocument"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      finishInfo -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.FinishContextDocument"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      failInfo -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.FailContextDocument"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      failDep -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : { "$uuid" : "4ef18506-3b38-4235-9933-d7da831247a6"} , "parent" : { "$uuid" : "4236c133-be50-4169-8e0c-096eded5bfeb"} , "document" : { "_typeHint" : "persistence.FailContextDocument"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}"""
    )
    messageJsonMap.foreach { case (message, json) =>
      val logDocument = LogDocument(testUUID, id, Some(parentId), message, time)

      val gratedDocument = graters.logDocumentGrater.asDBObject(logDocument)
      val jsonLogDocument = JSON.serialize(gratedDocument)

      val diff = compareJson(json, jsonLogDocument)

      if (json.isEmpty) {
        jsonLogDocument should be(json)
      } else {
        diff.toString should be("")
        jsonLogDocument should be(json)
      }

      val ungratedDBObject = JSON.parse(json).asInstanceOf[DBObject]
      ungratedDBObject.toString should be(json)

      val ungratedDeployDocument = graters.logDocumentGrater.asObject(new MongoDBObject(ungratedDBObject))
      ungratedDeployDocument should be(logDocument)
    }
  }

  "DeployRecordDocument" should "build from a deploy record" in {
    testDocument should be(
      DeployRecordDocument(
        testUUID,
        Some(testUUID.toString),
        testTime,
        ParametersDocument("Tester", "Deploy", "test-project", "1", "CODE", "test-recipe", Nil, Map("branch"->"master")),
        RunState.Completed
      )
    )
  }

  it should "serialise to BSON" in {
    val dbObject = graters.deployGrater.asDBObject(testDocument)
    dbObject should not be null
    val encoder = new BasicBSONEncoder()
    val bytes = encoder.encode(dbObject)
    bytes should not be null
  }

  it should "never change without careful thought and testing of migration" in {
    val dataModelDump = """{ "_id" : { "$uuid" : "39320f5b-7837-4f47-85f7-bc2d780e19f6"} , "stringUUID" : "39320f5b-7837-4f47-85f7-bc2d780e19f6" , "startTime" : { "$date" : "2012-11-08T17:20:00.000Z"} , "parameters" : { "deployer" : "Tester" , "deployType" : "Deploy" , "projectName" : "test::project" , "buildId" : "1" , "stage" : "TEST" , "recipe" : "test-recipe" , "hostList" : [ "testhost1" , "testhost2"] , "tags" : { "branch" : "test"}} , "status" : "Completed"}"""

    val deployDocument = RecordConverter(comprehensiveDeployRecord).deployDocument
    val gratedDeployDocument = graters.deployGrater.asDBObject(deployDocument)

    val jsonDeployDocument = JSON.serialize(gratedDeployDocument)
    val diff = compareJson(dataModelDump, jsonDeployDocument)
    diff.toString should be("")
    jsonDeployDocument should be(dataModelDump)

    val ungratedDBObject = JSON.parse(dataModelDump).asInstanceOf[DBObject]
    ungratedDBObject.toString should be(dataModelDump)

    val ungratedDeployDocument = graters.deployGrater.asObject(new MongoDBObject(ungratedDBObject))
    ungratedDeployDocument should be(deployDocument)
  }

  "ApiKey" should "never change without careful thought and testing of migration" in {
    val time = new DateTime(2012,11,8,17,20,0)
    val lastTime = new DateTime(2013,1,8,17,20,0)

    val apiKeyDump = """{ "application" : "test-application" , "_id" : "hfeklwb34uiopfnu34io2tr_-fffDS" , "issuedBy" : "Test User" , "created" : { "$date" : "2012-11-08T17:20:00.000Z"} , "lastUsed" : { "$date" : "2013-01-08T17:20:00.000Z"} , "callCounters" : { "counter1" : 34 , "counter2" : 2345}}"""

    val apiKey = ApiKey("test-application", "hfeklwb34uiopfnu34io2tr_-fffDS", "Test User", time, Some(lastTime), Map("counter1" -> 34L, "counter2" -> 2345L))
    val gratedApiKey = riffraffGraters.apiGrater.asDBObject(apiKey)

    val jsonApiKey = JSON.serialize(gratedApiKey)
    val diff = compareJson(apiKeyDump, jsonApiKey)
    diff.toString should be("")
    jsonApiKey should be(apiKeyDump)

    val ungratedDBObject = JSON.parse(apiKeyDump).asInstanceOf[DBObject]
    ungratedDBObject.toString should be(apiKeyDump)

    val ungratedApiKey = riffraffGraters.apiGrater.asObject(new MongoDBObject(ungratedDBObject))
    ungratedApiKey should be(apiKey)
  }

  "ContinuousDeploymentConfig" should "never change without careful thought and testing of migration" in {
    val uuid = UUID.fromString("ae46a1c9-7762-4f05-9f32-6d6cd8c496c7")
    val lastTime = new DateTime(2013,1,8,17,20,0)
    val configDump = """{ "_id" : { "$uuid" : "ae46a1c9-7762-4f05-9f32-6d6cd8c496c7"} , "projectName" : "test::project" , "stage" : "TEST" , "recipe" : "default" , "branchMatcher" : "^master$" , "enabled" : true , "user" : "Test user" , "lastEdited" : { "$date" : "2013-01-08T17:20:00.000Z"}}"""

    val config = ContinuousDeploymentConfig(uuid, "test::project", "TEST", "default", Some("^master$"), true, "Test user", lastTime)
    val gratedConfig = riffraffGraters.continuousDeployConfigGrater.asDBObject(config)

    val jsonConfig = JSON.serialize(gratedConfig)
    val diff = compareJson(configDump, jsonConfig)
    diff.toString should be("")
    jsonConfig should be(configDump)

    val ungratedDBObject = JSON.parse(configDump).asInstanceOf[DBObject]
    ungratedDBObject.toString should be(configDump)

    val ungratedConfig = riffraffGraters.continuousDeployConfigGrater.asObject(new MongoDBObject(ungratedDBObject))
    ungratedConfig should be(config)
  }

  lazy val graters = new DocumentGraters {
    def loader = Some(getClass.getClassLoader)
  }

  lazy val riffraffGraters = new RiffRaffGraters {
    def loader = Some(getClass.getClassLoader)
  }

}
