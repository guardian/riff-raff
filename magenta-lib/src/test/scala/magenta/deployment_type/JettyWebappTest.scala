package magenta

import java.io.File
import java.util.UUID

import tasks._
import org.json4s._
import org.json4s.JsonDSL._
import tasks.BlockFirewall
import tasks.CheckUrls
import tasks.CopyFile
import tasks.Service
import tasks.UnblockFirewall
import org.json4s.JsonAST.JString
import tasks.WaitForPort
import org.json4s.JsonAST.JArray
import org.scalatest.{FlatSpec, Matchers}
import magenta.deployment_type.JettyWebapp

class JettyWebappTest  extends FlatSpec with Matchers {
  implicit val fakeKeyRing = KeyRing(SystemUser(None))
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())

  "jetty web app package type" should "have a deploy action" in {
    val p = DeploymentPackage("webapp", Seq.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))

    val host = Host("host_name")

    JettyWebapp.perHostActions("deploy")(p)(reporter, host, fakeKeyRing) should be (List(
      BlockFirewall(host as "jetty"),
      CopyFile(host as "jetty", "/tmp/packages/webapp/", "/jetty-apps/webapp/"),
      Service(host as "jetty", "webapp"),
      WaitForPort(host, 8080, 1 * 60 * 1000),
      CheckUrls(host, 8080, List("/webapp/management/healthcheck"), 2 * 60 * 1000, 5),
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

    JettyWebapp.perHostActions("deploy")(p)(reporter, host, fakeKeyRing) should be (List(
      BlockFirewall(host as "jetty"),
      CopyFile(host as "jetty", "/tmp/packages/webapp/", "/jetty-apps/webapp/"),
      Service(host as "jetty", "webapp"),
      WaitForPort(host, 8080, 1 * 60 * 1000),
      CheckUrls(host, 8080, urls, 2 * 60 * 1000, 5),
      UnblockFirewall(host as "jetty")
    ))

  }

  it should "allow servicename to be overridden for copy and restart" in {
    val p = DeploymentPackage("webapp", Seq.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))
    val p2 = DeploymentPackage("webapp", Seq.empty, Map("servicename"->"microapps"), "jetty-webapp", new File("/tmp/packages/webapp"))

    val host = Host("host_name")

    JettyWebapp.perHostActions("deploy")(p)(reporter, host, fakeKeyRing) should (contain (
      CopyFile(host as "jetty", "/tmp/packages/webapp/", "/jetty-apps/webapp/")
    ) and contain (
      Service(host as "jetty", "webapp")
    ))

    JettyWebapp.perHostActions("deploy")(p2)(reporter, host, fakeKeyRing) should (contain (
      CopyFile(host as "jetty", "/tmp/packages/webapp/", "/jetty-apps/microapps/")
    ) and contain (
      Service(host as "jetty", "microapps")
    ))
  }

  it should "have useful default copyRoots" in {
    val p = DeploymentPackage("webapp", Seq.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))
    JettyWebapp.copyRoots(p) should be(List(""))
  }

  it should "add missing slashes" in {
    val p = DeploymentPackage("webapp", Seq.empty, Map("copyRoots" -> JArray(List("solr/conf/", "app"))), "jetty-webapp", new File("/tmp/packages/webapp"))
    val host = Host("host_name")
    JettyWebapp.perHostActions("deploy")(p)(reporter, host, fakeKeyRing) should (contain (
      CopyFile(host as "jetty", "/tmp/packages/webapp/solr/conf/", "/jetty-apps/webapp/solr/conf/")
    ) and contain (
      CopyFile(host as "jetty", "/tmp/packages/webapp/app/", "/jetty-apps/webapp/app/")
    ))
  }

  it should "have multiple copy file tasks for multiple roots in mirror mode" in {
    val p = DeploymentPackage("d2index", Seq.empty, Map("copyRoots" -> JArray(List("solr/conf/", "webapp")), "copyMode" -> CopyFile.MIRROR_MODE), "jetty-webapp", new File("/tmp/packages/d2index"))
    val host = Host("host_name")

    JettyWebapp.perHostActions("deploy")(p)(reporter, host, fakeKeyRing) should be (List(
      BlockFirewall(host as "jetty"),
      CopyFile(host as "jetty", "/tmp/packages/d2index/solr/conf/", "/jetty-apps/d2index/solr/conf/", CopyFile.MIRROR_MODE),
      CopyFile(host as "jetty", "/tmp/packages/d2index/webapp/", "/jetty-apps/d2index/webapp/", CopyFile.MIRROR_MODE),
      Service(host as "jetty", "d2index"),
      WaitForPort(host, 8080, 1 * 60 * 1000),
      CheckUrls(host, 8080, List("/d2index/management/healthcheck"), 2 * 60 * 1000, 5),
      UnblockFirewall(host as "jetty")
    ))
  }
}
