package magenta

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import tasks._
import java.io.File
import net.liftweb.util.TimeHelpers._
import net.liftweb.json.Implicits._
import net.liftweb.json.JsonAST.JValue


class PackageTypeTest extends FlatSpec with ShouldMatchers {

  "Amazon Web Services S3" should "have a uploadStaticFiles action" in {

    val data: Map[String, JValue] = Map(
      "bucket" -> "bucket-1234",
      "cacheControl" -> "no-cache"
    )

    val p = Package("myapp", Set.empty, data, "aws-s3", new File("/tmp/packages/static-files"))

    val deploy = AmazonWebServicesS3(p)

    deploy.perAppActions("uploadStaticFiles")(Stage("CODE")) should be (
      List(S3Upload(Stage("CODE"),"bucket-1234",new File("/tmp/packages/static-files"),Some("no-cache")))
    )
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