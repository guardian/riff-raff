package com.gu.deploy

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import tasks._
import java.io.File

class WebappPackageTypeTest extends FlatSpec with ShouldMatchers {

  "jetty web app package type" should "have a deploy action" in {
    val p = Package("webapp", Set.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))

    val jetty = new JettyWebappPackageType(p)
    val host = Host("host_name")

    jetty.actions("deploy")(host) should be (List(
      BlockFirewall(host as "jetty"),
      CopyFile(host as "jetty", "/tmp/packages/webapp", "/jetty-apps/"),
      RestartAndWait(host as "jetty", "webapp", "8080"),
      UnblockFirewall(host as "jetty")
    ))
  }

  it should "have let port be overriden" in {
    val p = Package("webapp", Set.empty, Map("port" -> "80"), "jetty-webapp", new File("/tmp/packages/webapp"))

    val jetty = new JettyWebappPackageType(p)
    val host = Host("host_name") as "jetty"

    jetty.actions("deploy")(host) should be (List(
      BlockFirewall(host),
      CopyFile(host, "/tmp/packages/webapp", "/jetty-apps/"),
      RestartAndWait(host, "webapp", "80"),
      UnblockFirewall(host)
    ))
  }




}