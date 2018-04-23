package persistence

import java.util.UUID

import awscala.dynamodbv2.DynamoDB
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.gu.scanamo.ops._
import com.gu.scanamo.{DynamoFormat, Scanamo}
import conf.Configuration
import org.joda.time.DateTime

trait DynamoRepository {
  val stage = Configuration.stage

  val client: AmazonDynamoDB = {
    if (stage == "DEV") DynamoDB.local()
    else Configuration.dynamoDb.client
  }

  def exec[A](ops: ScanamoOps[A]): A = Scanamo.exec(client)(ops)
  def tablePrefix: String

  lazy val tableName = s"$tablePrefix-$stage"

  implicit val uuidFormat =
    DynamoFormat.coercedXmap[UUID, String, IllegalArgumentException](UUID.fromString)(_.toString)

  implicit val jodaStringFormat =
    DynamoFormat.coercedXmap[DateTime, String, IllegalArgumentException](DateTime.parse)(_.toString)
}
