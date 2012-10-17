package magenta

import java.io.File
import java.util.NoSuchElementException
import net.liftweb.json.JsonAST.{JInt, JArray, JString, JValue}

case class Package(
  name: String,
  pkgApps: Set[App],
  pkgSpecificData: Map[String, JValue],
  pkgTypeName: String,
  srcDir: File) {

  def mkAction(name: String): Action = pkgType.mkAction(name)

  lazy val pkgType = pkgTypeName match {
    case "asg-elb" => AutoScalingWithELB(this)
    case "jetty-webapp" => JettyWebappPackageType(this)
    case "resin-webapp" => ResinWebappPackageType(this)
    case "django-webapp" => DjangoWebappPackageType(this)
    case "executable-jar-webapp" => ExecutableJarWebappPackageType(this)
    case "file" => FilePackageType(this)
    case "puppet" => PuppetPackageType(this)
    case "demo" => DemoPackageType(this)
    case "aws-s3" => AmazonWebServicesS3(this)
    case unknown => sys.error("Package type %s of package %s is unknown" format (unknown, name))
  }

  val data = pkgType.defaultData ++ pkgSpecificData
  def stringData(key: String): String = data(key) match { case JString(s) => s case _ => throw new NoSuchElementException() }
  def intData(key: String): BigInt = data(key) match { case JInt(i) => i case _ => throw new NoSuchElementException() }
  def arrayStringData(key: String) = data.getOrElse(key, List.empty) match {
    case JArray(l) => l flatMap { case JString(s) => Some(s) case _ => None }
    case _ => List.empty
  }

  val apps = pkgApps
}