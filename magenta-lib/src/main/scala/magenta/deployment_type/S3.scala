package magenta.deployment_type

import magenta.Datum
import magenta.deployment_type.param_reads.PatternValue
import magenta.tasks.{S3Upload, S3 => S3Tasks, SSM}

object S3 extends DeploymentType with BucketParameters {
  val name = "aws-s3"
  val documentation = "For uploading files into an S3 bucket."

  val prefixStage =
    Param("prefixStage", "Prefix the S3 bucket key with the target stage")
      .default(true)
  val prefixPackage =
    Param("prefixPackage", "Prefix the S3 bucket key with the package name")
      .default(true)
  val prefixStack =
    Param("prefixStack", "Prefix the S3 bucket key with the target stack")
      .default(true)
  val prefixApp = Param[Boolean](
    name = "prefixApp",
    documentation = """
        |Whether to prefix `app` to the S3 location instead of `package`.
        |
        |When `true` `prefixPackage` will be ignored and `app` will be used over `package`, useful if `package` and `app` don't align.
        |""".stripMargin
  ).default(false)

  val publicReadAcl = Param[Boolean](
    "publicReadAcl",
    """
      |Whether the uploaded artifacts should be given the PublicRead Canned ACL.
      |
      |If public access to the bucket is restricted (which is the AWS default), `publicReadAcl` should be `false`.
      |If the bucket content needs to be public, it's better to control this via the bucket policy.
      |
      |See https://docs.aws.amazon.com/AmazonS3/latest/userguide/acl-overview.html.
    """.stripMargin,
    optional = false
  )

  val cacheControl = Param[List[PatternValue]](
    "cacheControl",
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

  val surrogateControl = Param[List[PatternValue]](
    "surrogateControl",
    """
      |Same as cacheControl, but for setting the surrogate-control cache header, which is used by Fastly.
    """.stripMargin
  ).default(Nil)

  val mimeTypes = Param[Map[String, String]](
    "mimeTypes",
    """
      |A map of file extension to MIME type.
      |
      |When a file is uploaded with a file extension that is in this map, the Content-Type header will be set to the
      |MIME type provided.
      |
      |The example below adds the MIME type for Firefox plugins so that they install correctly rather than opening in
      |the browser.
      |
      |    "mimeTypes": {
      |      "xpi": "application/x-xpinstall"
      |    }
    """.stripMargin
  ).default(Map.empty)

  val uploadStaticFiles = Action(
    name = "uploadStaticFiles",
    documentation = """
        |Uploads the deployment files to an S3 bucket. In order for this to work, magenta must have credentials that are
        |valid to write to the bucket in the specified location.
        |
        |Each file path and name is used to generate the key, optionally prefixing the target stage and the package name
        |to the key. The generated key looks like: `/<bucketName>/<targetStage>/<packageName>/<filePathAndName>`.
        |
        |Alternatively, you can specify a pathPrefixResource (eg `s3-path-prefix`) to lookup the path prefix, giving you
        |greater control. The generated key looks like: `/<pathPrefix>/<filePathAndName>`.
        """.stripMargin
  ) { (pkg, resources, target) =>
    {
      implicit val keyRing = resources.assembleKeyring(target, pkg)
      implicit val artifactClient = resources.artifactClient
      val reporter = resources.reporter

      val bucketName = {
        val bucket = getTargetBucketFromConfig(pkg, target, reporter)
        S3Tasks.getBucketName(
          bucket,
          SSM.withSsmClient(keyRing, target.region, resources),
          resources.reporter
        )
      }

      val maybePackageOrAppName: Option[String] = (
        prefixPackage(pkg, target, reporter),
        prefixApp(pkg, target, reporter)
      ) match {
        case (_, true)      => Some(pkg.app.name)
        case (true, false)  => Some(pkg.name)
        case (false, false) => None
      }

      val prefix: String = S3Upload.prefixGenerator(
        stack =
          if (prefixStack(pkg, target, reporter)) Some(target.stack) else None,
        stage =
          if (prefixStage(pkg, target, reporter)) Some(target.parameters.stage)
          else None,
        packageOrAppName = maybePackageOrAppName
      )

      List(
        S3Upload(
          target.region,
          bucket = bucketName,
          paths = Seq(pkg.s3Package -> prefix),
          cacheControlPatterns = cacheControl(pkg, target, reporter),
          surrogateControlPatterns = surrogateControl(pkg, target, reporter),
          extensionToMimeType = mimeTypes(pkg, target, reporter),
          publicReadAcl = publicReadAcl(pkg, target, reporter)
        )
      )
    }
  }

  def defaultActions = List(uploadStaticFiles)
}
