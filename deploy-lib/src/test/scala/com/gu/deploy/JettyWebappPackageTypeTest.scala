package com.gu.deploy

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import tasks._

class JettyWebappPackageTypeTest extends FlatSpec with ShouldMatchers {

  "jetty web app package type" should "have a deploy action" in {
    val p = Package("webapp", Set.empty, "jetty-webapp")

    val jetty = new JettyWebappPackageType(p)

    jetty.actions("deploy")(Host("host_name")) should be (List(
      BlockFirewall(),
      CopyFile("packages/webapp", "/jetty-apps/webapp/"),
      RestartAndWait(),
      UnblockFirewall()
    ))
  }


}