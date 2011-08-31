package com.gu.deploy

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import tasks._
import java.io.File

class JettyWebappPackageTypeTest extends FlatSpec with ShouldMatchers {

  "jetty web app package type" should "have a deploy action" in {
    val p = Package("webapp", Set.empty, Map.empty, "jetty-webapp", new File("/tmp"))

    val jetty = new JettyWebappPackageType(p)
    val host = Host("host_name")

    jetty.actions("deploy")(host) should be (List(
      BlockFirewall(host),
      CopyFile(host, "packages/webapp", "/jetty-apps/"),
      RestartAndWait(host, "webapp", "8080"),
      UnblockFirewall(host)
    ))
  }

  "jetty web app package type" should "have let port be overriden" in {
    val p = Package("webapp", Set.empty, Map("port" -> "80"), "jetty-webapp", new File("/tmp"))

    val jetty = new JettyWebappPackageType(p)
    val host = Host("host_name")

    jetty.actions("deploy")(host) should be (List(
      BlockFirewall(host),
      CopyFile(host, "packages/webapp", "/jetty-apps/"),
      RestartAndWait(host, "webapp", "80"),
      UnblockFirewall(host)
    ))
  }


}