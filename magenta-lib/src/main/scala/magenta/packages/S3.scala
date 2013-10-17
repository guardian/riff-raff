package magenta.packages

import net.liftweb.json.JsonAST._
import java.io.File
import magenta.json.JValueExtractable
import magenta.tasks.S3Upload

object S3 extends PackageType {
  val name = "aws-s3"

  val params = Seq(bucket, bucketResource, prefixPackage, prefixPackage, cacheControl)

  val prefixStage = Param("prefixStage", Some(true))
  val prefixPackage = Param("prefixPackage", Some(true))

  //required configuration, you cannot upload without setting these
  val bucket = Param[String]("bucket")
  val bucketResource = Param[String]("bucketResource")

  val cacheControl = Param[List[PatternValue]]("cacheControl")

  implicit object PatternValueExtractable extends JValueExtractable[List[PatternValue]] {
    def extract(json: JValue) = json match {
      case JString(default) => Some(List(PatternValue(".*", default)))
      case JArray(patternValues) => Some(for {
        JObject(patternValue) <- patternValues
        JField("pattern", JString(regex)) <- patternValue
        JField("value", JString(value)) <- patternValue
      } yield PatternValue(regex, value))
      case _ => throw new IllegalArgumentException("cacheControl is a required parameter")
    }
  }

  def perAppActions = {
    case "uploadStaticFiles" => (pkg) => (deployInfo, parameters) => {
      assert(bucket.get(pkg).isDefined != bucketResource.get(pkg).isDefined, "One, and only one, of bucket or bucketResource must be specified")
      val bucketName = bucket.get(pkg) getOrElse {
        assert(pkg.apps.size == 1, s"The $name package type, in conjunction with bucketResource, cannot be used when more than one app is specified")
        val data = deployInfo.firstMatchingData(bucketResource(pkg), pkg.apps.head, parameters.stage.name)
        assert(data.isDefined, s"Cannot find resource value for ${bucketResource(pkg)} (${pkg.apps.head} in ${parameters.stage.name})")
        data.get.value
      }
      List(
        S3Upload(parameters.stage,
          bucketName,
          new File(pkg.srcDir.getPath + "/"),
          cacheControl(pkg),
          prefixStage = prefixStage(pkg),
          prefixPackage = prefixPackage(pkg)
        )
      )
    }
  }
}

case class PatternValue(pattern: String, value: String) {
  lazy val regex = pattern.r
}
