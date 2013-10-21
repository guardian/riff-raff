package magenta.deployment_type

import net.liftweb.json.JsonAST._
import java.io.File
import magenta.json.JValueExtractable
import magenta.tasks.S3Upload

object S3 extends DeploymentType {
  val name = "aws-s3"
  val documentation =
    """
      |Provides one deploy action that uploads the package files to an S3 bucket. In order for this to work, magenta
      |must have credentials that are valid to write to the bucket in the sepcified location.
    """.stripMargin

  val prefixStage = Param("prefixStage").default(true)
  val prefixPackage = Param("prefixPackage").default(true)

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
