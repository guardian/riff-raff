package deployment

import dispatch._
import sbt._
import dispatch.Request.encode_%
import magenta.json.DeployInfoJsonReader
import magenta.{MessageBroker, Build}

object DeployInfo {
  lazy val hostList = {
    import sys.process._
    DeployInfoJsonReader.parse("/opt/bin/deployinfo.json".!!)
  }
}
