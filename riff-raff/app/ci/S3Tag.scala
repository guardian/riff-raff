package ci

import conf.Configuration
import play.api.libs.json.Json

object S3Tag {
  lazy val bucketName = Configuration.tag.aws.bucketName.get
  implicit lazy val client = Configuration.tag.aws.client

  def of(build: CIBuild): Option[Seq[String]] = {
    val tagContent = S3Location.contents(S3Location(bucketName, s"${build.jobName}/${build.number}/tags.json"))
    tagContent.flatMap(c => Json.parse(c).asOpt[Seq[String]])
  }
}
