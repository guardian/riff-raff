package test

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import notification.{HookConfig, HookCriteria, HookAction}
import com.mongodb.casbah.Imports._
import com.ning.http.client.Realm.AuthScheme
import java.util.UUID
import magenta._
import persistence.{ParametersDocument, DeployRecordDocument}
import org.joda.time.DateTime

class HooksTest extends FlatSpec with ShouldMatchers {
  "HookAction" should "serialise and deserialise" in {
    val action = HookAction("http://localhost:80/test", true)
    val dbo = action.toDBO
    dbo.as[String]("url") should be("http://localhost:80/test")
    dbo.as[Boolean]("enabled") should be(true)
    HookAction.fromDBO(dbo) should be(Some(action))
  }

  it should "create an authenticated request" in {
    val action = HookConfig("testProject", "TEST", "http://simon:bobbins@localhost:80/test", true, "Mr. Tester")
    val req = action.request(testDeployParams)
    req.auth should be(Some(("simon", "bobbins", AuthScheme.BASIC)))
  }

  it should "create a plain request" in {
    val action = HookConfig("testProject", "TEST", "http://localhost:80/test", true, "Mr. Tester")
    val req = action.request(testDeployParams)
    req.auth should be(None)
  }

  it should "substitute parameters" in {
    val action = HookConfig("testProject", "TEST", "http://localhost:80/test?build=%deploy.build%", true, "Mr. Tester")
    val req = action.request(testDeployParams)
    req.url should be("http://localhost:80/test?build=23")
  }

  it should "escape substitute parameters" in {
    val action = HookConfig("testProject", "TEST", "http://localhost:80/test?project=%deploy.project%", true, "Mr. Tester")
    val req = action.request(testDeployParams)
    req.url should be("http://localhost:80/test?project=test%3A%3Aproject")
  }

  it should "substitute tag parameters" in {
    val action = HookConfig("testProject", "TEST", "http://localhost:80/test?build=%deploy.build%&sha=%deploy.tag.vcsRevision%", true, "Mr. Tester")
    val req = action.request(testDeployParams)
    req.url should be("http://localhost:80/test?build=23&sha=9110598b83a908d7882ac4e3cd4b643d7d8bc54e")
  }

  "HookCriteria" should "serialise and deserialise" in {
    val criteria = HookCriteria("testProject","CODE")
    val dbo = criteria.toDBO
    dbo.as[String]("projectName") should be("testProject")
    dbo.as[String]("stageName") should be("CODE")
    HookCriteria.fromDBO(dbo) should be(Some(criteria))
  }

  val testUUID = UUID.fromString("758fa00e-e9da-41e0-b31f-1af417e333a1")
  val startTime = new DateTime(2013,9,23,13,23,33)
  val testDeployParams = DeployRecordDocument(
    testUUID,
    Some(testUUID.toString),
    startTime,
    ParametersDocument(
      "Mr. Tester",
      "Deploy",
      "test::project",
      "23",
      "TEST",
      "default",
      List("host1.dom", "host2.dom"),
      Map("vcsRevision" -> "9110598b83a908d7882ac4e3cd4b643d7d8bc54e", "riffraff-domain" -> "10-252-94-200")
    ),
    RunState.Completed
  )
}
