package magenta
package json

import org.scalatest.{Matchers, FlatSpec}
import fixtures._


class DeployInfoTest  extends FlatSpec with Matchers {
  val deployInfoSample = """
  {"hosts":[
    {"group":"a", "stage":"CODE", "app":"microapp-cache", "hostname":"machost01.dc-code.gnl"},
    {"group":"b", "stage":"CODE", "app":"microapp-cache", "hostname":"machost51.dc-code.gnl"},
    {"group":"a", "stage":"QA", "app":"microapp-cache", "hostname":"machost01.dc-qa.gnl"}
  ],
  "data":{ "credentials:aws": [
    {"app":"microapp-cache", "stage":"CODE", "value":"AAA"},
    {"app":"frontend-article", "stage":"CODE", "value":"CCC"},
    {"app":"frontend-.*", "stage":"CODE", "value":"BBB"},
    {"app":"frontend-gallery", "stage":"CODE", "value":"SHADOWED"},
    {"app":"microapp-cache", "stage":".*", "value":"DDD"},
    {"app":"status-app", "stage":"PROD", "stack":"ophan", "value":"ophan-status"},
    {"app":"status-app", "stage":"PROD", "stack":"contentapi", "value":"capi-status"}
  ]}}"""

  "json parser" should "parse deployinfo json" in {
    val parsed = DeployInfoJsonReader.parse(deployInfoSample)
    parsed.hosts.size should be (3)
    parsed.data.values.map(_.size).reduce(_+_) should be (7)

    val host = parsed.hosts(0)
    host should be (Host("machost01.dc-code.gnl", Set(App("microapp-cache")), CODE.name, tags = Map("group" -> "a")))
   }

  "host transposing" should "retain host ordering with no groups" in {
    val hosts = List(Host("test1"), Host("test2"), Host("test3"))
    hosts.transposeBy(_.tags.getOrElse("group","")) should be(hosts)
  }

  it should "retain host ordering with one group" in {
    val hosts = List(
      Host("test1", tags=Map("group"->"one")),
      Host("test2", tags=Map("group"->"one")),
      Host("test3", tags=Map("group"->"one"))
    )
    hosts.transposeBy(_.tags.getOrElse("group","")) should be(hosts)
  }

  it should "interleave two groups of identical length" in {
    val hosts = List(
      Host("test1", tags=Map("group"->"one")),
      Host("test2", tags=Map("group"->"one")),
      Host("test3", tags=Map("group"->"two")),
      Host("test4", tags=Map("group"->"two"))
    )
    val orderedHosts = List(
      Host("test1", tags=Map("group"->"one")),
      Host("test3", tags=Map("group"->"two")),
      Host("test2", tags=Map("group"->"one")),
      Host("test4", tags=Map("group"->"two"))
    )
    hosts.transposeBy(_.tags.getOrElse("group","")) should be(orderedHosts)
  }

  it should "interleave two groups different lengths" in {
    val hosts = List(
      Host("test1", tags=Map("group"->"one")),
      Host("test2", tags=Map("group"->"one")),
      Host("test3", tags=Map("group"->"two")),
      Host("test4", tags=Map("group"->"two")),
      Host("test5", tags=Map("group"->"two"))
    )
    val orderedHosts = List(
      Host("test1", tags=Map("group"->"one")),
      Host("test3", tags=Map("group"->"two")),
      Host("test2", tags=Map("group"->"one")),
      Host("test4", tags=Map("group"->"two")),
      Host("test5", tags=Map("group"->"two"))
    )
    hosts.transposeBy(_.tags.getOrElse("group","")) should be(orderedHosts)
  }

  it should "interleave three groups different lengths" in {
    val hosts = List(
      Host("test1", tags=Map("group"->"one")),
      Host("test2", tags=Map("group"->"one")),
      Host("test3", tags=Map("group"->"two")),
      Host("test4", tags=Map("group"->"two")),
      Host("test5", tags=Map("group"->"three")),
      Host("test6", tags=Map("group"->"three")),
      Host("test7", tags=Map("group"->"one")),
      Host("test8", tags=Map("group"->"two")),
      Host("test9", tags=Map("group"->"one")),
      Host("testA", tags=Map("group"->"one")),
      Host("testB", tags=Map("group"->"three"))
    )
    val orderedHosts = List(
      Host("test1", tags=Map("group"->"one")),
      Host("test5", tags=Map("group"->"three")),
      Host("test3", tags=Map("group"->"two")),
      Host("test2", tags=Map("group"->"one")),
      Host("test6", tags=Map("group"->"three")),
      Host("test4", tags=Map("group"->"two")),
      Host("test7", tags=Map("group"->"one")),
      Host("testB", tags=Map("group"->"three")),
      Host("test8", tags=Map("group"->"two")),
      Host("test9", tags=Map("group"->"one")),
      Host("testA", tags=Map("group"->"one"))
    )
    hosts.transposeBy(_.tags.getOrElse("group","")) should be(orderedHosts)
  }

  "deploy info" should "provide a distinct list of host attributes" in {
    val parsed = DeployInfoJsonReader.parse(deployInfoSample)
    parsed.knownHostStages.size should be(2)
    parsed.knownHostApps.size should be(1)
  }

  it should "match an exact app and stage" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)
    di.firstMatchingData("credentials:aws",App("microapp-cache"),Stage("CODE"), UnnamedStack) should be(Some(Datum(None, "microapp-cache","CODE","AAA",None)))
  }

  it should "match on a regex" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)
    di.firstMatchingData("credentials:aws",App("frontend-front"),Stage("CODE"), UnnamedStack) should be(Some(Datum(None, "frontend-.*","CODE","BBB",None)))
  }

  it should "match the first in the list" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)
    di.firstMatchingData("credentials:aws",App("frontend-article"),Stage("CODE"), UnnamedStack) should be(Some(Datum(None, "frontend-article","CODE","CCC",None)))
    di.firstMatchingData("credentials:aws",App("frontend-gallery"),Stage("CODE"), UnnamedStack) should be(Some(Datum(None, "frontend-.*","CODE","BBB",None)))
  }

  it should "not match bigger app or stage names" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)
    di.firstMatchingData("credentials:aws",App("frontend-article"),Stage("CODE2"), UnnamedStack) should be(None)
    di.firstMatchingData("credentials:aws",App("frontend-article"),Stage("NEWCODE"), UnnamedStack) should be(None)
    di.firstMatchingData("credentials:aws",App("new-microapp-cache"),Stage("CODE"), UnnamedStack) should be(None)
    di.firstMatchingData("credentials:aws",App("microapp-cache-again"),Stage("CODE"), UnnamedStack) should be(None)
  }

  it should "filter based on named stacks" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)
    di.firstMatchingData("credentials:aws",App("status-app"),PROD, NamedStack("ophan")) should be(
      Some(Datum(Some("ophan"), "status-app", "PROD", "ophan-status", None)))
    di.firstMatchingData("credentials:aws",App("status-app"),PROD, NamedStack("contentapi")) should be(
      Some(Datum(Some("contentapi"), "status-app", "PROD", "capi-status", None)))
  }

  it should "provide a list of hosts filtered by stage" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)

    di.forParams(testParams().copy(stage = Stage("CODE"))).hosts should be(
      List(
        Host("machost01.dc-code.gnl",Set(App("microapp-cache")), CODE.name,None, None, Map("group" -> "a")),
        Host("machost51.dc-code.gnl",Set(App("microapp-cache")), CODE.name,None, None,Map("group" -> "b"))
      )
    )
  }

  it should "provide a list of hosts with only those explicitly specified" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)

    di.forParams(testParams().copy(stage = CODE,hostList = List("machost01.dc-code.gnl"))).hosts should be(
      List(Host("machost01.dc-code.gnl",Set(App("microapp-cache")), CODE.name,None, None, Map("group" -> "a"))))
  }

}