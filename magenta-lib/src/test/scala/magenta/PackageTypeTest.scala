package magenta

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import tasks._
import java.io.File
import net.liftweb.util.TimeHelpers._
import net.liftweb.json.Implicits._
import net.liftweb.json.JsonAST.{JValue, JArray, JString}


class PackageTypeTest extends FlatSpec with ShouldMatchers {

  implicit def string2file(s: String) = new File(s)

  "jetty web app package type" should "have a deploy action" in {
    val p = Package("webapp", Set.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))

    val jetty = new JettyWebappPackageType(p)
    val host = Host("host_name")

    jetty.perHostActions("deploy")(host) should be (List(
      BlockFirewall(host as "jetty"),
      CopyFile(host as "jetty", "/tmp/packages/webapp/", "/jetty-apps/webapp/"),
      Restart(host as "jetty", "webapp"),
      WaitForPort(host, "8080", 1 minute),
      CheckUrls(host, "8080", List(), 20 seconds),
      UnblockFirewall(host as "jetty")
    ))
  }

  it should "have an upload artifacts action" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "1234"
    )
    val p = Package("webapp", Set.empty, data, "jetty-webapp", new File("/tmp/packages/webapp"))

    val jetty = new JettyWebappPackageType(p)

    jetty.perAppActions("uploadArtifacts")(Stage("CODE")) should be (
      List(S3Upload(Stage("CODE"),"1234","/tmp/packages/webapp","/tmp/packages",None))
    )
  }

  it should "have the ability to specify the artifact to upload to S3" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "1234",
      "artifact" -> "my.jar"
    )
    val p = Package("webapp", Set.empty, data, "jetty-webapp", new File("/tmp/packages/webapp"))

    val jetty = new JettyWebappPackageType(p)

    jetty.perAppActions("uploadArtifacts")(Stage("CODE")) should be (
      List(S3Upload(Stage("CODE"),"1234","/tmp/packages/webapp/my.jar","/tmp/packages",None))
    )
  }

  it should "have an upload static files action" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "1234",
      "staticDir" -> "staticFiles",
      "cacheControlHeader" -> "public, max-age=300"
    )
    val p = Package("webapp", Set.empty, data, "jetty-webapp", new File("/tmp/packages/webapp"))

    val jetty = new JettyWebappPackageType(p)

    jetty.perAppActions("uploadStaticFiles")(Stage("CODE")) should be (
      List(S3Upload(Stage("CODE"),"1234","/tmp/packages/webapp/staticFiles","/tmp/packages/webapp",
        Some("public, max-age=300")))
    )
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
      CheckUrls(host, "8080", urls, 20 seconds),
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
  
  "executable web app package type" should "have a default user of jvmuser" in {
    
    val webappPackage = ExecutableJarWebappPackageType(
      Package("foo", Set.empty, Map.empty, "executable-jar-webapp", new File("."))
    )

    webappPackage.user should be ("jvmuser")
    
  }
  
  it should "inherit defaults from base webapp" in {
    val webappPackage = ExecutableJarWebappPackageType(
      Package("foo", Set.empty, Map.empty, "executable-jar-webapp", new File("."))
    )

    webappPackage.port should be ("8080")
    webappPackage.serviceName should be ("foo")
  }

  "django web app package type" should "have a deploy action" in {
    val webappDirectory = new File("/tmp/packages/webapp")
    webappDirectory.mkdirs()
    for (file <- webappDirectory.listFiles()) {
      file.delete()
    }
    val specificBuildFile = File.createTempFile("webbapp-build.7", "", webappDirectory)

    val p = Package("webapp", Set.empty, Map.empty, "django-webapp", webappDirectory)
    val django = new DjangoWebappPackageType(p)
    val host = Host("host_name")

    django.perHostActions("deploy")(host) should be (List(
      BlockFirewall(host as "django"),
      CopyFile(host as "django", specificBuildFile.getPath, "/django-apps/"),
      ApacheGracefulStop(host as "django"),
      Link(host as "django", "/django-apps/" + specificBuildFile.getName, "/django-apps/webapp"),
      ApacheStart(host as "django"),
      WaitForPort(host, "80", 1 minute),
      UnblockFirewall(host as "django")
    ))
  }
}