package ci

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{ListObjectsRequest, ObjectListing}
import controllers.Logging

import scala.annotation.tailrec
import scala.io.Source

case class S3Location(bucket: String, path: String)

object S3Location extends Logging {
  import collection.convert.wrapAsScala._

  val client = new AmazonS3Client()

  def contents(location: S3Location): String =
    Source.fromInputStream(client.getObject(location.bucket, location.path).getObjectContent).mkString

  val maxKeysInBucketListing = 1000 // AWS won't return more than this, even if you set the parameter to a larger value

  def all(bucket: String): Seq[S3Location] = {
    @tailrec
    def pageListings(listings: Seq[ObjectListing], listing: ObjectListing): Seq[ObjectListing] = {
      if (!listing.isTruncated) {
        listings
      } else {
        val nextBatch = client.listNextBatchOfObjects(listing)
        pageListings(listings :+ nextBatch, nextBatch)
      }
    }

    val initialListing = client.listObjects(new ListObjectsRequest().withBucketName(bucket).withMaxKeys(maxKeysInBucketListing))
    for {
      summaries <- pageListings(Seq(initialListing), initialListing)
      summary <- summaries.getObjectSummaries
    } yield S3Location(bucket, summary.getKey)
  }
}
