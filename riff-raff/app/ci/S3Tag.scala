package ci

import conf.Config
import magenta.artifact.S3Path
import play.api.libs.json.Json

class S3Tag(config: Config) {
  lazy val bucketName = config.tag.aws.bucketName
  implicit lazy val client = config.tag.aws.client

  import cats.syntax.either._

  def of(build: CIBuild): Option[Seq[String]] = {
    for {
      bucket <- bucketName
      tagContent <- S3Path(
        bucket,
        s"${build.jobName}/${build.number}/tags.json"
      ).fetchContentAsString().toOption
      tags <- Json.parse(tagContent).asOpt[Seq[String]]
    } yield tags
  }
}
