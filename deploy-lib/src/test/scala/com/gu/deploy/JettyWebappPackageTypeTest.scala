package com.gu.deploy

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import tasks._

class JettyWebappPackageTypeTest extends FlatSpec with ShouldMatchers {

  "jetty web app package type" should "have a deploy action" in {
    val p = Package("webapp", Set.empty, "jetty-webapp")

    val jetty = new JettyWebappPackageType(p)
    val host = Host("host_name")

    jetty.actions("deploy")(host) should be (List(
      BlockFirewall(host),
      CopyFile(host, "packages/webapp", "/jetty-apps/webapp/"),
      RestartAndWait(host),
      UnblockFirewall(host)
    ))
  }


}