package magenta.artifact

import magenta.Build
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class S3ArtifactTest extends AnyFlatSpec with Matchers {
  "S3Artifact" should "create an S3Artifact instance from a build and bucket" in {
    val build = Build("testProject", "123")
    val artifact = S3YamlArtifact(build, "myBucket")
    artifact.bucket should be("myBucket")
    artifact.key should be("testProject/123/")
  }
}
