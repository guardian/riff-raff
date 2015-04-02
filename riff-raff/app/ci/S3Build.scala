package ci

import ci.teamcity.Job
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectListing
import controllers.Logging
import org.joda.time.DateTime
import play.api.libs.json.{Json, JsPath, Reads}
import rx.lang.scala.Observable

import scala.annotation.tailrec
import scala.io.Source
import scala.util.{Failure, Try}
import scala.util.control.NonFatal

case class S3Build(
  id: Long,
  jobName: String,
  jobId: String,
  branchName: String,
  number: String,
  startTime: DateTime
) extends CIBuild

case class S3Project(id: String, name: String) extends Job

object S3Build extends Logging {
  val client = new AmazonS3Client()

  val bucketName = "travis-ci-artifact-test"

  import collection.convert.wrapAsScala._
  import play.api.libs.functional.syntax._

  import utils.Json.DefaultJodaDateReads
  implicit val reads: Reads[S3Build] = (
    (JsPath \ "BuildNumber").read[String].map(_.toLong) and
    (JsPath \ "ProjectName").read[String] and
    (JsPath \ "ProjectName").read[String] and
    (JsPath \ "Branch").read[String] and
    (JsPath \ "BuildNumber").read[String] and
    (JsPath \ "StartTime").read[DateTime]
  )(S3Build.apply _)

  def all(): Seq[CIBuild] = {
    val objects = listAllObjects(bucketName).flatMap(_.getObjectSummaries)

    val buildJsonLocations = for (o <- objects if o.getKey.endsWith("build.json")) yield o.getKey

    for {
      location <- buildJsonLocations
      build <- (Try {
        Json.parse(Source.fromInputStream(client.getObject(bucketName, location).getObjectContent).mkString).as[S3Build]
      } recoverWith  {
        case NonFatal(e) => {
          log.error(s"Error parsing $location", e)
          Failure(e)
        }
      }).toOption
    } yield build
  }

  def listAllObjects(bucket: String): Seq[ObjectListing] = {
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
    pageListings(Seq(client.listObjects(bucket)), initialList)
  }
}
