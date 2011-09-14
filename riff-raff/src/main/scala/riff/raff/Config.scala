package riff.raff

import com.gu.deploy.json.DeployInfoJsonReader


object Config {

  lazy val stages = List("CODE", "QA", "TEST", "RELEASE", "STAGE", "PROD")

  // TODO: this needs to be re-read on a schedule
  lazy val parsedDeployInfo = {
     import sys.process._
     DeployInfoJsonReader.parse("contrib/deployinfo.json".!!)
   }

}