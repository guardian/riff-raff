package magenta

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import java.io.File
import org.json4s._
import org.json4s.JsonDSL._
import fixtures._
import magenta.deployment_type.{S3, Django, ExecutableJarWebapp, PatternValue}
import magenta.tasks._

class DeploymentTypeTest extends FlatSpec with ShouldMatchers {
  implicit val fakeKeyRing = KeyRing(SystemUser(None))

  "Deployment types" should "automatically register params in the params Seq" in {
    S3.params should have size 8
    S3.params.map(_.name).toSet should be(Set("prefixStage","prefixPackage","prefixStack","bucket","publicReadAcl","bucketResource","cacheControl","mimeTypes"))
  }

  it should "throw a NoSuchElementException if a required parameter is missing" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "bucket-1234"
    )

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-s3", new File("/tmp/packages/static-files"))

    val thrown = evaluating {
      S3.perAppActions("uploadStaticFiles")(p)(lookupSingleHost, parameters(Stage("CODE")), UnnamedStack) should be (
        List(S3Upload(UnnamedStack, Stage("CODE"),"bucket-1234",new File("/tmp/packages/static-files"), List(PatternValue(".*", "no-cache"))))
      )
    } should produce [NoSuchElementException]

    thrown.getMessage should equal ("Package myapp [aws-s3] requires parameter cacheControl of type List")
  }

  "Amazon Web Services S3" should "have a uploadStaticFiles action" in {

    val data: Map[String, JValue] = Map(
      "bucket" -> "bucket-1234",
      "cacheControl" -> "no-cache"
    )

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-s3", new File("/tmp/packages/static-files"))

    S3.perAppActions("uploadStaticFiles")(p)(lookupSingleHost, parameters(Stage("CODE")), UnnamedStack) should be (
      List(S3Upload(UnnamedStack, Stage("CODE"),"bucket-1234",new File("/tmp/packages/static-files"), List(PatternValue(".*", "no-cache"))))
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

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-s3", new File("/tmp/packages/static-files"))

    S3.perAppActions("uploadStaticFiles")(p)(lookupSingleHost, parameters(Stage("CODE")), UnnamedStack) should be (
      List(
        S3Upload(UnnamedStack, Stage("CODE"),"bucket-1234",new File("/tmp/packages/static-files"),
          List(PatternValue("^sub", "no-cache"), PatternValue(".*", "public; max-age:3600")))
      )
    )
  }

  "executable web app package type" should "have a default user of jvmuser" in {
    
    val webappPackage =  DeploymentPackage("foo", Seq.empty, Map.empty, "executable-jar-webapp", new File("."))

    ExecutableJarWebapp.user(webappPackage) should be ("jvmuser")
  }
  
  it should "inherit defaults from base webapp" in {
    val webappPackage = DeploymentPackage("foo", Seq.empty, Map.empty, "executable-jar-webapp", new File("."))

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

    val p = DeploymentPackage("webapp", Seq.empty, Map.empty, "django-webapp", webappDirectory)
    val host = Host("host_name")

    Django.perHostActions("deploy")(p)(host, fakeKeyRing) should be (List(
      BlockFirewall(host as "django"),
      CompressedCopy(host as "django", Some(specificBuildFile), "/django-apps/"),
      Link(host as "django", Some("/django-apps/" + specificBuildFile.getName), "/django-apps/webapp"),
      ApacheGracefulRestart(host as "django"),
      CleanupOldDeploys(host as "django", 0, "/django-apps/", "webapp"),
      WaitForPort(host, 80, 1 * 60 * 1000),
      CheckUrls(host, 80, List.empty, 120000, 5),
      UnblockFirewall(host as "django")
    ))
  }

  def parameters(stage: Stage) = DeployParameters(Deployer("tester"), Build("project", "version"), stage)
}