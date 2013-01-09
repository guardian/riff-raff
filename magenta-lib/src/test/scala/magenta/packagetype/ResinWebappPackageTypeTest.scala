package magenta

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import java.io.File
import tasks._
import tasks.BlockFirewall
import tasks.CheckUrls
import tasks.CopyFile
import tasks.Restart
import tasks.WaitForPort
import net.liftweb.util.TimeHelpers._
import net.liftweb.json.Implicits._
import net.liftweb.json.JsonAST.{JArray, JString}

class ResinWebappPackageTypeTest extends FlatSpec with ShouldMatchers {
  "resin web app package type" should "have a deploy action" in {
    val p = Package("webapp", Set.empty, Map.empty, "resin-webapp", new File("/tmp/packages/webapp"))

    val resin = new ResinWebappPackageType(p)
    val host = Host("host_name")

    resin.perHostActions("deploy")(host) should be (List(
      BlockFirewall(host as "resin"),
      CopyFile(host as "resin", "/tmp/packages/webapp/", "/resin-apps/webapp/"),
      Restart(host as "resin", "webapp"),
      WaitForPort(host, "8080", 1 minute),
      CheckUrls(host, "8080", List("/webapp/management/healthcheck"), 2 minutes, 5),
      UnblockFirewall(host as "resin")
    ))
  }

  it should "allow port to be overriden" in {
    val basic = Package("webapp", Set.empty, Map.empty, "resin-webapp", new File("/tmp/packages/webapp"))
    basic.data("port") should be (JString("8080"))
    basic.stringData("port") should be ("8080")

    val overridden = Package("webapp", Set.empty, Map("port" -> "80"), "resin-webapp", new File("/tmp/packages/webapp"))
    overridden.data("port") should be (JString("80"))
    overridden.stringData("port") should be ("80")
  }

  it should "allow urls to check after deploy" in {
    val urls = JArray(List("/test", "/xx"))

    val basic = Package("webapp", Set.empty, Map("healthcheck_paths" -> urls), "resin-webapp", new File("/tmp/packages/webapp"))
    basic.data("healthcheck_paths") should be (urls)
  }

  it should "check urls when specified" in {
    val urls = List("/test", "/xx")
    val urls_json = JArray(urls map { JString(_)})

    val p = Package("webapp", Set.empty, Map("healthcheck_paths" -> urls_json), "resin-webapp", new File("/tmp/packages/webapp"))
    val resin = new ResinWebappPackageType(p)
    val host = Host("host_name")

    resin.perHostActions("deploy")(host) should be (List(
      BlockFirewall(host as "resin"),
      CopyFile(host as "resin", "/tmp/packages/webapp/", "/resin-apps/webapp/"),
      Restart(host as "resin", "webapp"),
      WaitForPort(host, "8080", 1 minute),
      CheckUrls(host, "8080", urls, 2 minutes, 5),
      UnblockFirewall(host as "resin")
    ))
  }

  it should "allow wait and check times to be overriden" in {
    val basic = Package("webapp", Set.empty, Map.empty, "resin-webapp", new File("/tmp/packages/webapp"))
    val resinBasic = new ResinWebappPackageType(basic)
    resinBasic.waitDuration should be(60 seconds)
    resinBasic.checkDuration should be(120 seconds)

    val overridden = Package("webapp", Set.empty, Map("waitseconds" -> 120, "checkseconds" -> 60), "resin-webapp", new File("/tmp/packages/webapp"))
    val resinOverridden = new ResinWebappPackageType(overridden)
    resinOverridden.waitDuration should be(120 seconds)
    resinOverridden.checkDuration should be(60 seconds)
  }


}
