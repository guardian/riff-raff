package magenta
package json

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers


class DeployInfoTest  extends FlatSpec with ShouldMatchers {
  val deployInfoSample = """
  {"hosts":[
{"group":"a", "stage":"CODE", "app":"microapp-cache", "hostname":"machost01.dc-code.gnl"},
{"group":"b", "stage":"CODE", "app":"microapp-cache", "hostname":"machost51.dc-code.gnl"},
{"group":"a", "stage":"QA", "app":"microapp-cache", "hostname":"machost01.dc-qa.gnl"}
  ],
  "keys":[
  {"app":"microapp-cache", "stage":"CODE", "accesskey":"AAA"},
  {"app":"frontend-article", "stage":"CODE", "accesskey":"CCC"},
  {"app":"frontend-.*", "stage":"CODE", "accesskey":"BBB"},
  {"app":"frontend-gallery", "stage":"CODE", "accesskey":"SHADOWED"},
  {"app":"microapp-cache", "stage":".*", "accesskey":"DDD"}
  ]}"""

  "json parser" should "parse deployinfo json" in {
    val parsed = DeployInfoJsonReader.parse(deployInfoSample)
    parsed.hosts.size should be (3)
    parsed.keys.size should be (5)

    val host = parsed.hosts(0)
    host should be (Host("machost01.dc-code.gnl", Set(App("microapp-cache")), "CODE"))
//
//     host.group should be ("a")
//     host.hostname should be ("machost01.dc-code.gnl")
//     host.app should be ("microapp-cache")
//     host.stage should be ("CODE")
   }

  "deploy info" should "provide a distinct list of host attributes" in {
    val parsed = DeployInfoJsonReader.parse(deployInfoSample)
    parsed.knownHostStages.size should be(2)
    parsed.knownHostApps.size should be(1)
  }

  it should "match an exact app and stage" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)
    di.firstMatchingKey(App("microapp-cache"),"CODE") should be(Some(Key("microapp-cache","CODE","AAA",None)))
  }

  it should "match on a regex" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)
    di.firstMatchingKey(App("frontend-front"),"CODE") should be(Some(Key("frontend-.*","CODE","BBB",None)))
  }

  it should "match the first in the list" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)
    di.firstMatchingKey(App("frontend-article"),"CODE") should be(Some(Key("frontend-article","CODE","CCC",None)))
    di.firstMatchingKey(App("frontend-gallery"),"CODE") should be(Some(Key("frontend-.*","CODE","BBB",None)))
  }

  it should "not match bigger app or stage names" in {
    val di = DeployInfoJsonReader.parse(deployInfoSample)
    di.firstMatchingKey(App("frontend-article"),"CODE2") should be(None)
    di.firstMatchingKey(App("frontend-article"),"NEWCODE") should be(None)
    di.firstMatchingKey(App("new-microapp-cache"),"CODE") should be(None)
    di.firstMatchingKey(App("microapp-cache-again"),"CODE") should be(None)
  }

}