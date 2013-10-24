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
    case UpdateFastlyConfigPackageType.name => UpdateFastlyConfigPackageType(this)
    case unknown => sys.error("Package type %s of package %s is unknown" format (unknown, name))
  }

  lazy val _data: Map[String, JValue] = pkgType.defaultData ++ pkgSpecificData

  object data {

    def string(key: String): String =
      stringOpt(key) getOrElse missing(key, "string")

    def stringOpt(key: String): Option[String] =
      _data.get(key) collect { case JString(s) => s }

    def bigInt(key: String): BigInt =
      bigIntOpt(key) getOrElse missing(key, "number")

    def bigIntOpt(key: String): Option[BigInt] =
      _data.get(key) collect { case JInt(i) => i }

    def int(key: String): Int = bigInt(key).toInt

    def long(key: String): Long = bigInt(key).toLong

    def boolean(key: String): Boolean =
      booleanOpt(key) getOrElse missing(key, "boolean")

    def booleanOpt(key: String): Option[Boolean] =
      _data.get(key) collect { case JBool(b) => b }

    def stringArray(key: String): List[String] =
      _data.get(key)
        .collect { case JArray(l) => l collect { case JString(s) => s } }
        .getOrElse(Nil)

    def arrayPatternValue(key: String): List[PatternValue] =
      for {
        JArray(patternValues) <- _data(key)
        JObject(patternValue) <- patternValues
        JField("pattern", JString(regex)) <- patternValue
        JField("value", JString(value)) <- patternValue
      } yield PatternValue(regex, value)

    private def missing(key: String, `type`: String): Nothing =
      throw new NoSuchElementException(s"""Expected field "$key" of type ${`type`}""")
  }

  val apps = pkgApps
}
