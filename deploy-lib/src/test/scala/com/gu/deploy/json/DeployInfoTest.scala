package com.gu.deploy.json

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import com.gu.deploy.{App, Host}

class DeployInfoTest  extends FlatSpec with ShouldMatchers {
  val deployInfoSample = """
  {"hosts":[
{"group":"a", "stage":"CODE", "app":"microapp-cache", "hostname":"machost01.dc-code.gnl"},
{"group":"b", "stage":"CODE", "app":"microapp-cache", "hostname":"machost51.dc-code.gnl"},
{"group":"a", "stage":"QA", "app":"microapp-cache", "hostname":"machost01.dc-qa.gnl"}
  
  ]}"""
  "json parser" should "parse deployinfo json" in {
     val parsed = DeployInfoJsonReader.parse(deployInfoSample)
     parsed.size should be (3)
     val host = parsed(0)
     host should be (Host("machost01.dc-code.gnl", Set(App("microapp-cache")), "CODE"))
//
//     host.group should be ("a")
//     host.hostname should be ("machost01.dc-code.gnl")
//     host.app should be ("microapp-cache")
//     host.stage should be ("CODE")
   }

}