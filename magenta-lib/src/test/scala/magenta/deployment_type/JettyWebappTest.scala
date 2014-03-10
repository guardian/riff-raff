package magenta

import java.io.File
import tasks._
import net.liftweb.json.JsonAST.{JArray, JString}
import net.liftweb.util.TimeHelpers._
import net.liftweb.json.Implicits._
import tasks.BlockFirewall
import tasks.CheckUrls
import tasks.CopyFile
import tasks.Restart
import tasks.UnblockFirewall
import net.liftweb.json.JsonAST.JString
import tasks.WaitForPort
import net.liftweb.json.JsonAST.JArray
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import magenta.deployment_type.JettyWebapp

class JettyWebappTest  extends FlatSpec with ShouldMatchers {
  "jetty web app package type" should "have a deploy action" in {
    val p = DeploymentPackage("webapp", Seq.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))

    val host = Host("host_name")

    JettyWebapp.perHostActions("deploy")(p)(host) should be (List(
      BlockFirewall(host as "jetty"),
      CopyFile(host as "jetty", "/tmp/packages/webapp/", "/jetty-apps/webapp/"),
      Restart(host as "jetty", "webapp"),
      WaitForPort(host, 8080, 1 minute),
      CheckUrls(host, 8080, List("/webapp/management/healthcheck"), 2 minutes, 5),
      UnblockFirewall(host as "jetty")
    ))
  }

  it should "allow port to be overriden" in {
    val basic = DeploymentPackage("webapp", Seq.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))
    JettyWebapp.port(basic) should be (8080)

    val overridden = DeploymentPackage("webapp", Seq.empty, Map("port" -> "80"), "jetty-webapp", new File("/tmp/packages/webapp"))
    JettyWebapp.port(overridden) should be (80)
  }

  it should "check urls when specified" in {
    val urls = List("/test", "/xx")
    val urls_json = JArray(urls map { JString(_)})

    val p = DeploymentPackage("webapp", Seq.empty, Map("healthcheck_paths" -> urls_json), "jetty-webapp", new File("/tmp/packages/webapp"))
    val host = Host("host_name")

    JettyWebapp.perHostActions("deploy")(p)(host) should be (List(
      BlockFirewall(host as "jetty"),
      CopyFile(host as "jetty", "/tmp/packages/webapp/", "/jetty-apps/webapp/"),
      Restart(host as "jetty", "webapp"),
      WaitForPort(host, 8080, 1 minute),
      CheckUrls(host, 8080, urls, 2 minutes, 5),
      UnblockFirewall(host as "jetty")
    ))

  }

  it should "allow servicename to be overridden for copy and restart" in {
    val p = DeploymentPackage("webapp", Seq.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))
    val p2 = DeploymentPackage("webapp", Seq.empty, Map("servicename"->"microapps"), "jetty-webapp", new File("/tmp/packages/webapp"))

    val host = Host("host_name")

    JettyWebapp.perHostActions("deploy")(p)(host) should (contain[Task] (
      CopyFile(host as "jetty", "/tmp/packages/webapp/", "/jetty-apps/webapp/")
    ) and contain[Task] (
      Restart(host as "jetty", "webapp")
    ))

    JettyWebapp.perHostActions("deploy")(p2)(host) should (contain[Task] (
      CopyFile(host as "jetty", "/tmp/packages/webapp/", "/jetty-apps/microapps/")
    ) and contain[Task] (
      Restart(host as "jetty", "microapps")
    ))
  }

  it should "have useful default copyRoots" in {
    val p = DeploymentPackage("webapp", Seq.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))
    JettyWebapp.copyRoots(p) should be(List(""))
  }

  it should "add missing slashes" in {
    val p = DeploymentPackage("webapp", Seq.empty, Map("copyRoots" -> JArray(List("solr/conf/", "app"))), "jetty-webapp", new File("/tmp/packages/webapp"))
    val host = Host("host_name")
    JettyWebapp.perHostActions("deploy")(p)(host) should (contain[Task] (
      CopyFile(host as "jetty", "/tmp/packages/webapp/solr/conf/", "/jetty-apps/webapp/solr/conf/")
    ) and contain[Task] (
      CopyFile(host as "jetty", "/tmp/packages/webapp/app/", "/jetty-apps/webapp/app/")
    ))
  }

  it should "have multiple copy file tasks for multiple roots in mirror mode" in {
    val p = DeploymentPackage("d2index", Seq.empty, Map("copyRoots" -> JArray(List("solr/conf/", "webapp")), "copyMode" -> CopyFile.MIRROR_MODE), "jetty-webapp", new File("/tmp/packages/d2index"))
    val host = Host("host_name")

    JettyWebapp.perHostActions("deploy")(p)(host) should be (List(
      BlockFirewall(host as "jetty"),
      CopyFile(host as "jetty", "/tmp/packages/d2index/solr/conf/", "/jetty-apps/d2index/solr/conf/", CopyFile.MIRROR_MODE),
      CopyFile(host as "jetty", "/tmp/packages/d2index/webapp/", "/jetty-apps/d2index/webapp/", CopyFile.MIRROR_MODE),
      Restart(host as "jetty", "d2index"),
      WaitForPort(host, 8080, 1 minute),
      CheckUrls(host, 8080, List("/d2index/management/healthcheck"), 2 minutes, 5),
      UnblockFirewall(host as "jetty")
    ))
  }
}
