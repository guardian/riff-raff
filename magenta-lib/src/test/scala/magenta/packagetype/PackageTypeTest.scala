package magenta

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import tasks._
import java.io.File
import net.liftweb.util.TimeHelpers._
import net.liftweb.json.Implicits._
import net.liftweb.json.JsonAST._
import fixtures._
import magenta.packages.{Django, ExecutableJarWebapp, S3, PatternValue}
import magenta.tasks.S3


class PackageTypeTest extends FlatSpec with ShouldMatchers {

  "Amazon Web Services S3" should "have a uploadStaticFiles action" in {

    val data: Map[String, JValue] = Map(
      "bucket" -> "bucket-1234",
      "cacheControl" -> "no-cache"
    )

    val p = Package("myapp", Set.empty, data, "aws-s3", new File("/tmp/packages/static-files"))

    S3.perAppActions("uploadStaticFiles")(p)(deployinfoSingleHost, parameters(Stage("CODE"))) should be (
      List(S3Upload(Stage("CODE"),"bucket-1234",new File("/tmp/packages/static-files"), List(PatternValue(".*", "no-cache"))))
    )
  }

  it should "take a pattern list for cache control" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "bucket-1234",
      "cacheControl" -> JArray(List(
        JObject(List(JField("pattern", JString("^sub")), JField("value", JString("no-cache")))),
        JObject(List(JField("pattern", JString(".*")), JField("value", JString("public; max-age:3600"))))
      ))
    )

    val p = Package("myapp", Set.empty, data, "aws-s3", new File("/tmp/packages/static-files"))

    S3.perAppActions("uploadStaticFiles")(p)(deployinfoSingleHost, parameters(Stage("CODE"))) should be (
      List(
        S3Upload(Stage("CODE"),"bucket-1234",new File("/tmp/packages/static-files"),
          List(PatternValue("^sub", "no-cache"), PatternValue(".*", "public; max-age:3600")))
      )
    )
  }
  
  "executable web app package type" should "have a default user of jvmuser" in {
    
    val webappPackage =  Package("foo", Set.empty, Map.empty, "executable-jar-webapp", new File("."))

    ExecutableJarWebapp.user(webappPackage) should be ("jvmuser")
  }
  
  it should "inherit defaults from base webapp" in {
    val webappPackage = Package("foo", Set.empty, Map.empty, "executable-jar-webapp", new File("."))

    ExecutableJarWebapp.port(webappPackage) should be (8080)
    ExecutableJarWebapp.servicename(webappPackage) should be ("foo")
  }

  "django web app package type" should "have a deploy action" in {
    val webappDirectory = new File("/tmp/packages/webapp")
    webappDirectory.mkdirs()
    for (file <- webappDirectory.listFiles()) {
      file.delete()
    }
    val specificBuildFile = File.createTempFile("webbapp-build.7", "", webappDirectory)

    val p = Package("webapp", Set.empty, Map.empty, "django-webapp", webappDirectory)
    val host = Host("host_name")

    Django.perHostActions("deploy")(p)(host) should be (List(
      BlockFirewall(host as "django"),
      CompressedCopy(host as "django", Some(specificBuildFile), "/django-apps/"),
      Link(host as "django", Some("/django-apps/" + specificBuildFile.getName), "/django-apps/webapp"),
      ApacheGracefulRestart(host as "django"),
      WaitForPort(host, 80, 1 minute),
      CheckUrls(host, 80, List.empty, 120000, 5),
      UnblockFirewall(host as "django")
    ))
  }

  def parameters(stage: Stage) = DeployParameters(Deployer("tester"), Build("project", "version"), stage)
}