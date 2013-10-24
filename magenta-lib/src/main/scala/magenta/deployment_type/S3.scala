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
      |
      |Each file path and name is used to generate the key, optionally prefixing the target stage and the package name
      |to the key. The generated key looks like: `/<bucketName>/<targetStage>/<packageName>/<filePathAndName>`.
    """.stripMargin

  val prefixStage = Param("prefixStage",
    "Prefix the S3 bucket key with the target stage").default(true)
  val prefixPackage = Param("prefixPackage",
    "Prefix the S3 bucket key with the package name").default(true)

  //required configuration, you cannot upload without setting these
  val bucket = Param[String]("bucket", "S3 bucket to upload package files to (see also `bucketResource`)")
  val bucketResource = Param[String]("bucketResource",
    """Deploy Info resource key to use to look up the S3 bucket to which the package files should be uploaded.
      |
      |This parameter is mutually exclusive with `bucket`, which can be used instead if you upload to the same bucket
      |regardless of the target stage.
    """.stripMargin
  )

  val cacheControl = Param[List[PatternValue]]("cacheControl",
    """
      |Set the cache control header for the uploaded files. This can take two forms, but in either case the format of
      |the cache control value itself must be a valid HTTP `Cache-Control` value such as `public, max-age=315360000`.
      |
      |In the first form the cacheControl parameter is set to a JSON string and the value will apply to all files
      |uploaded to the S3 bucket.
      |
      |In the second form the cacheControl parameter is set to an array of JSON objects, each of which have `pattern`
      |and `value` keys. `pattern` is a Java regular expression whilst `value` must be a valid `Cache-Control` header.
      |For each file being uploaded, the array of regular expressions is evaluated against the file path and name. The
      |first regular expression that matches the file is used to determine the cache control value. If there is no match
      |then no cache control will be set.
      |
      |In the example below, if the file path matches either of the first two regular expressions then the cache control
      |header will be set to ten years (i.e. never expire). If neither of the first two match then the catch all regular
      |expression of the last object will match, setting the cache control header to one hour.
      |
      |    "cacheControl": [
      |      {
      |        "pattern": "^js/lib/",
      |        "value": "max-age=315360000"
      |      },
      |      {
      |        "pattern": "^\d*\.\d*\.\d*/",
      |        "value": "max-age=315360000"
      |      },
      |      {
      |        "pattern": ".*",
      |        "value": "max-age=3600"
      |      }
      |    ]
    """.stripMargin
  )

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
