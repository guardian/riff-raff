package magenta
package json

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import fixtures._


class DeployInfoTest  extends FlatSpec with ShouldMatchers {
  val deployInfoSample = """
  {"hosts":[
{"group":"a", "stage":"CODE", "app":"microapp-cache", "hostname":"machost01.dc-code.gnl"},
{"group":"b", "stage":"CODE", "app":"microapp-cache", "hostname":"machost51.dc-code.gnl"},
{"group":"a", "stage":"QA", "app":"microapp-cache", "hostname":"machost01.dc-qa.gnl"}
  ],
  "data":{ "aws-keys": [
  {"app":"microapp-cache", "stage":"CODE", "value":"AAA"},
  {"app":"frontend-article", "stage":"CODE", "value":"CCC"},
  {"app":"frontend-.*", "stage":"CODE", "value":"BBB"},
  {"app":"frontend-gallery", "stage":"CODE", "value":"SHADOWED"},
  {"app":"microapp-cache", "stage":".*", "value":"DDD"}
  ]}}"""

  "json parser" should "parse deployinfo json" in {
    val parsed = DeployInfoJsonReader.parse(deployInfoSample)
    parsed.hosts.size should be (3)
    parsed.data.values.map(_.size).reduce(_+_) should be (5)

    val host = parsed.hosts(0)
    host should be (Host("machost01.dc-code.gnl", Set(App("microapp-cache")), CODE.name, tags = Map("group" -> "a")))
   }

  "host transposing" should "retain host ordering with no groups" in {
    val hosts = List(Host("test1"), Host("test2"), Host("test3"))
    DeployInfo.transposeHostsByGroup(hosts) should be(hosts)
  }

  it should "retain host ordering with one group" in {
    val hosts = List(
      Host("test1", tags=Map("group"->"one")),
      Host("test2", tags=Map("group"->"one")),
      Host("test3", tags=Map("group"->"one"))
    )
    DeployInfo.transposeHostsByGroup(hosts) should be(hosts)
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
    DeployInfo.transposeHostsByGroup(hosts) should be(orderedHosts)
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
    DeployInfo.transposeHostsByGroup(hosts) should be(orderedHosts)
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
    DeployInfo.transposeHostsByGroup(hosts) should be(orderedHosts)
  }

  "deploy info" should "provide a distinct list of host attributes" in {
    val parsed = DeployInfoJsonReader.parse(deployInfoSample)
    parsed.knownHostStages.size should be(2)
    parsed.knownHostApps.size should be(1)
  }

  it should "match an exact app and stage" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)
    di.firstMatchingData("aws-keys",App("microapp-cache"),"CODE") should be(Some(Data("microapp-cache","CODE","AAA",None)))
  }

  it should "match on a regex" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)
    di.firstMatchingData("aws-keys",App("frontend-front"),"CODE") should be(Some(Data("frontend-.*","CODE","BBB",None)))
  }

  it should "match the first in the list" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)
    di.firstMatchingData("aws-keys",App("frontend-article"),"CODE") should be(Some(Data("frontend-article","CODE","CCC",None)))
    di.firstMatchingData("aws-keys",App("frontend-gallery"),"CODE") should be(Some(Data("frontend-.*","CODE","BBB",None)))
  }

  it should "not match bigger app or stage names" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)
    di.firstMatchingData("aws-keys",App("frontend-article"),"CODE2") should be(None)
    di.firstMatchingData("aws-keys",App("frontend-article"),"NEWCODE") should be(None)
    di.firstMatchingData("aws-keys",App("new-microapp-cache"),"CODE") should be(None)
    di.firstMatchingData("aws-keys",App("microapp-cache-again"),"CODE") should be(None)
  }

  it should "provide a list of hosts filtered by stage" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)

    di.forParams(testParams().copy(stage = Stage("CODE"))).hosts should be(
      List(
        Host("machost01.dc-code.gnl",Set(App("microapp-cache")), CODE.name,None,Map("group" -> "a")),
        Host("machost51.dc-code.gnl",Set(App("microapp-cache")), CODE.name,None,Map("group" -> "b"))
      )
    )
  }

  it should "provide a list of hosts with only those explicitly specified" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)

    di.forParams(testParams().copy(stage = CODE,hostList = List("machost01.dc-code.gnl"))).hosts should be(
      List(Host("machost01.dc-code.gnl",Set(App("microapp-cache")), CODE.name,None, Map("group" -> "a"))))
  }

}