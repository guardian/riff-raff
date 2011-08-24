package com.gu.deploy

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import tasks._

class TypesTest extends FlatSpec with ShouldMatchers {

  it should "Jetty Type should have a deploy action" in {
    val jetty = new JettyWebappPackageType()

    jetty.deployWebapp("webapp", Host("host_name")) should be (List(
      BlockFirewallTask(),
      CopyFileTask("packages/webapp", "/jetty-apps/webapp/"),
      RestartAndWaitTask(),
      UnblockFirewallTask()
    ))
  }


}