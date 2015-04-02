package ci

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectListing

import scala.annotation.tailrec
import scala.io.Source

case class S3Location(bucket: String, path: String)

object S3Location {
  import collection.convert.wrapAsScala._

  val client = new AmazonS3Client()

  def contents(location: S3Location): String =
    Source.fromInputStream(client.getObject(location.bucket, location.path).getObjectContent).mkString

  def all(bucket: String): Seq[S3Location] = {
    @tailrec
    def pageListings(listings: Seq[ObjectListing], listing: ObjectListing): Seq[ObjectListing] = {
      if (!listing.isTruncated) {
        Seq(listing)
      } else {
        val nextBatch = client.listNextBatchOfObjects(listing)
        pageListings(listings :+ nextBatch, nextBatch)
      }
    }

    val initialList = client.listObjects(bucket)
    for {
      summaries <- pageListings(Seq(client.listObjects(bucket)), initialList)
      summary <- summaries.getObjectSummaries
    } yield S3Location(bucket, summary.getKey)
  }
}
