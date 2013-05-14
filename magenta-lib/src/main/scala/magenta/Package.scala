package magenta

import java.io.File
import java.util.NoSuchElementException
import net.liftweb.json.JsonAST._

case class PatternValue(pattern: String, value: String) {
  lazy val regex = pattern.r
}

case class Package(
  name: String,
  pkgApps: Set[App],
  pkgSpecificData: Map[String, JValue],
  pkgTypeName: String,
  srcDir: File) {

  def mkAction(name: String): Action = pkgType.mkAction(name)

  lazy val pkgType = pkgTypeName match {
    case "autoscaling" => AutoScaling(this)
    case "asg-elb" => AutoScaling(this)
    case "elasticsearch" => ElasticSearch(this)
    case "jetty-webapp" => JettyWebappPackageType(this)
    case "resin-webapp" => ResinWebappPackageType(this)
    case "django-webapp" => DjangoWebappPackageType(this)
    case "executable-jar-webapp" => ExecutableJarWebappPackageType(this)
    case "file" => FilePackageType(this)
    case "demo" => DemoPackageType(this)
    case "aws-s3" => AmazonWebServicesS3(this)
    case UnzipToDocrootPackageType.name => UnzipToDocrootPackageType(this)
    case unknown => sys.error("Package type %s of package %s is unknown" format (unknown, name))
  }

  lazy val data = pkgType.defaultData ++ pkgSpecificData
  def stringData(key: String): String = data(key) match { case JString(s) => s case _ => throw new NoSuchElementException() }
  def stringDataOption(key: String): Option[String] = data.get(key) flatMap { case JString(s) => Some(s) case _ => None }
  def intData(key: String): BigInt = data(key) match { case JInt(i) => i case _ => throw new NoSuchElementException() }
  def booleanData(key: String): Boolean = data(key) match { case JBool(b) => b case _ => throw new NoSuchElementException() }
  def arrayStringData(key: String) = data.getOrElse(key, List.empty) match {
    case JArray(l) => l flatMap { case JString(s) => Some(s) case _ => None }
    case _ => List.empty
  }

  def arrayPatternValueData(key: String):List[PatternValue] = {
    for {
      JArray(patternValues) <- data(key)
      JObject(patternValue) <- patternValues
      JField("pattern", JString(regex)) <- patternValue
      JField("value", JString(value)) <- patternValue
    } yield PatternValue(regex, value)
  }

  val apps = pkgApps
}