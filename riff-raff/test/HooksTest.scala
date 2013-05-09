package test

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import notification.{HookCriteria, HookAction}
import com.mongodb.casbah.Imports._
import com.ning.http.client.Realm.AuthScheme

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
    val req = action.request
    req.auth should be(Some(("simon", "bobbins", AuthScheme.BASIC)))
  }

  it should "create a plain request" in {
    val action = HookAction("http://localhost:80/test", true)
    val req = action.request
    req.auth should be(None)
  }

  "HookCriteria" should "serialise and deserialise" in {
    val criteria = HookCriteria("testProject","CODE")
    val dbo = criteria.toDBO
    dbo.as[String]("projectName") should be("testProject")
    dbo.as[String]("stageName") should be("CODE")
    HookCriteria.fromDBO(dbo) should be(Some(criteria))
  }
}
