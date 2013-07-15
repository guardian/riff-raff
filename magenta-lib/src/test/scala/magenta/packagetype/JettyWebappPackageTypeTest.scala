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

class JettyWebappPackageTypeTest  extends FlatSpec with ShouldMatchers {
  "jetty web app package type" should "have a deploy action" in {
    val p = Package("webapp", Set.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))

    val jetty = new JettyWebappPackageType(p)
    val host = Host("host_name")

    jetty.perHostActions("deploy")(host) should be (List(
      BlockFirewall(host as "jetty"),
      CopyFile(host as "jetty", "/tmp/packages/webapp/", "/jetty-apps/webapp/"),
      Restart(host as "jetty", "webapp"),
      WaitForPort(host, "8080", 1 minute),
      CheckUrls(host, "8080", List("/webapp/management/healthcheck"), 2 minutes, 5),
      UnblockFirewall(host as "jetty")
    ))
  }

  it should "allow port to be overriden" in {
    val basic = Package("webapp", Set.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))
    basic.data("port") should be (JString("8080"))
    basic.stringData("port") should be ("8080")

    val overridden = Package("webapp", Set.empty, Map("port" -> "80"), "jetty-webapp", new File("/tmp/packages/webapp"))
    overridden.data("port") should be (JString("80"))
    overridden.stringData("port") should be ("80")
  }

  it should "allow urls to check after deploy" in {
    val urls = JArray(List("/test", "/xx"))

    val basic = Package("webapp", Set.empty, Map("healthcheck_paths" -> urls), "jetty-webapp", new File("/tmp/packages/webapp"))
    basic.data("healthcheck_paths") should be (urls)
  }

  it should "check urls when specified" in {
    val urls = List("/test", "/xx")
    val urls_json = JArray(urls map { JString(_)})

    val p = Package("webapp", Set.empty, Map("healthcheck_paths" -> urls_json), "jetty-webapp", new File("/tmp/packages/webapp"))
    val jetty = new JettyWebappPackageType(p)
    val host = Host("host_name")

    jetty.perHostActions("deploy")(host) should be (List(
      BlockFirewall(host as "jetty"),
      CopyFile(host as "jetty", "/tmp/packages/webapp/", "/jetty-apps/webapp/"),
      Restart(host as "jetty", "webapp"),
      WaitForPort(host, "8080", 1 minute),
      CheckUrls(host, "8080", urls, 2 minutes, 5),
      UnblockFirewall(host as "jetty")
    ))

  }

  it should "allow servicename to be overridden for copy and restart" in {
    val p = Package("webapp", Set.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))
    val jetty = new JettyWebappPackageType(p)
    val p2 = Package("webapp", Set.empty, Map("servicename"->"microapps"), "jetty-webapp", new File("/tmp/packages/webapp"))
    val jetty2 = new JettyWebappPackageType(p2)

    val host = Host("host_name")

    jetty.perHostActions("deploy")(host) should (contain[Task] (
      CopyFile(host as "jetty", "/tmp/packages/webapp/", "/jetty-apps/webapp/")
    ) and contain[Task] (
      Restart(host as "jetty", "webapp")
    ))

    jetty2.perHostActions("deploy")(host) should (contain[Task] (
      CopyFile(host as "jetty", "/tmp/packages/webapp/", "/jetty-apps/microapps/")
    ) and contain[Task] (
      Restart(host as "jetty", "microapps")
    ))
  }

  it should "have useful default copyRoots" in {
    val p = Package("webapp", Set.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))
    val jetty = new JettyWebappPackageType(p)
    jetty.copyRoots should be(List(""))
  }

  it should "add missing slashes" in {
    val p = Package("webapp", Set.empty, Map("copyRoots" -> JArray(List("solr/conf/", "webapp"))), "jetty-webapp", new File("/tmp/packages/webapp"))
    val jetty = new JettyWebappPackageType(p)
    jetty.copyRoots should be(List("solr/conf/", "webapp/"))
  }

  it should "have multiple copy file tasks for multiple roots in mirror mode" in {
    val p = Package("d2index", Set.empty, Map("copyRoots" -> JArray(List("solr/conf/", "webapp")), "copyMode" -> CopyFile.MIRROR_MODE), "jetty-webapp", new File("/tmp/packages/d2index"))
    val jetty = new JettyWebappPackageType(p)
    val host = Host("host_name")

    jetty.perHostActions("deploy")(host) should be (List(
      BlockFirewall(host as "jetty"),
      CopyFile(host as "jetty", "/tmp/packages/d2index/solr/conf/", "/jetty-apps/d2index/solr/conf/", CopyFile.MIRROR_MODE),
      CopyFile(host as "jetty", "/tmp/packages/d2index/webapp/", "/jetty-apps/d2index/webapp/", CopyFile.MIRROR_MODE),
      Restart(host as "jetty", "d2index"),
      WaitForPort(host, "8080", 1 minute),
      CheckUrls(host, "8080", List("/d2index/management/healthcheck"), 2 minutes, 5),
      UnblockFirewall(host as "jetty")
    ))
  }
}
