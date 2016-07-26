package ci

import conf.Configuration
import magenta.artifact.S3Path
import play.api.libs.json.Json

object S3Tag {
  lazy val bucketName = Configuration.tag.aws.bucketName
  implicit lazy val client = Configuration.tag.aws.client

  def of(build: CIBuild): Option[Seq[String]] = {
    for {
      bucket <- bucketName
      tagContent <- S3Path(bucket, s"${build.jobName}/${build.number}/tags.json").fetchContentAsString()
      tags <- Json.parse(tagContent).asOpt[Seq[String]]
    } yield tags
  }
}
