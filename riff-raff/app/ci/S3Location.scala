package ci

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{AmazonS3Exception, ListObjectsRequest, ObjectListing}
import controllers.Logging

import scala.annotation.tailrec
import scala.io.Source
import scala.util.Try

case class S3Location(bucket: String, path: String)

object S3Location extends Logging {
  import collection.convert.wrapAsScala._

  def contents(location: S3Location)(implicit client: AmazonS3): Option[String] =
    Try {
      Some(Source.fromInputStream(client.getObject(location.bucket, location.path).getObjectContent).mkString)
    }.recover {
      case e: AmazonS3Exception if e.getStatusCode == 404 => None
    }.get

  val maxKeysInBucketListing = 1000 // AWS won't return more than this, even if you set the parameter to a larger value

  def all(bucket: String)(implicit client: AmazonS3): Seq[S3Location] = {
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
