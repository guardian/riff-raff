package test

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import notification.{HookCriteria, HookAction}
import com.mongodb.casbah.Imports._
import com.ning.http.client.Realm.AuthScheme
import java.util.UUID
import magenta.{Stage, Deployer, Build, DeployParameters}

class HooksTest extends FlatSpec with ShouldMatchers {
  "HookAction" should "serialise and deserialise" in {
    val action = HookAction("http://localhost:80/test", true)
    val dbo = action.toDBO
    dbo.as[String]("url") should be("http://localhost:80/test")
    dbo.as[Boolean]("enabled") should be(true)
    HookAction.fromDBO(dbo) should be(Some(action))
  }

  it should "create an authenticated request" in {
    val action = HookAction("http://simon:bobbins@localhost:80/test", true)
    val req = action.request(testUUID, testDeployParams)
    req.auth should be(Some(("simon", "bobbins", AuthScheme.BASIC)))
  }

  it should "create a plain request" in {
    val action = HookAction("http://localhost:80/test", true)
    val req = action.request(testUUID, testDeployParams)
    req.auth should be(None)
  }

  it should "substitute parameters" in {
    val action = HookAction("http://localhost:80/test?build=%deploy.build%", true)
    val req = action.request(testUUID, testDeployParams)
    req.url should be("http://localhost:80/test?build=23")
  }

  it should "escape substitute parameters" in {
    val action = HookAction("http://localhost:80/test?project=%deploy.project%", true)
    val req = action.request(testUUID, testDeployParams)
    req.url should be("http://localhost:80/test?project=test%3A%3Aproject")
  }

  "HookCriteria" should "serialise and deserialise" in {
    val criteria = HookCriteria("testProject","CODE")
    val dbo = criteria.toDBO
    dbo.as[String]("projectName") should be("testProject")
    dbo.as[String]("stageName") should be("CODE")
    HookCriteria.fromDBO(dbo) should be(Some(criteria))
  }

  val testUUID = UUID.fromString("758fa00e-e9da-41e0-b31f-1af417e333a1")
  val testDeployParams = DeployParameters(
    Deployer("tester"),
    Build("test::project", "23"),
    Stage("TEST")
  )
}
