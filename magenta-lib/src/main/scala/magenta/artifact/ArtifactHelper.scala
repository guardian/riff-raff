package magenta.artifact

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{ListObjectsV2Request, S3ObjectSummary}

import scala.collection.JavaConverters._

object ArtifactHelper {
  def listObjects(path: S3Location)(implicit s3Client: AmazonS3) = {
    def listObjectsRec(path: S3Location, token: Option[String] = None): List[S3Object] = {
      val request = new ListObjectsV2Request()
        .withBucketName(path.bucket)
        .withPrefix(path.key)
        .withContinuationToken(token.orNull)
      val listing = s3Client.listObjectsV2(request)

      val objectSummaries = listing.getObjectSummaries.asScala.map{ summary =>
        S3Object(summary.getBucketName, summary.getKey, summary.getSize)
      }.toList
      Option(listing.getNextContinuationToken) match {
        case Some(nextMarker) => objectSummaries ::: listObjectsRec(path: S3Location, Some(nextMarker))
        case None => objectSummaries
      }
    }

    listObjectsRec(path)
  }


}
