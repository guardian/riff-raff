package persistence

import java.util.UUID

import com.gu.scanamo.{DynamoFormat, Scanamo, Table}
import com.gu.scanamo.ops._
import conf.Configuration
import org.joda.time.DateTime

trait DynamoRepository {

  val client = Configuration.dynamoDb.client
  def exec[A](ops: ScanamoOps[A]): A = Scanamo.exec(client)(ops)
  def tablePrefix: String

  // TODO set up Dynamo local
  val stage = if (Configuration.stage == "DEV") "CODE" else Configuration.stage
  lazy val tableName = s"$tablePrefix-$stage"

  implicit val uuidFormat =
    DynamoFormat.coercedXmap[UUID, String, IllegalArgumentException](UUID.fromString)(_.toString)

  implicit val jodaStringFormat =
    DynamoFormat.coercedXmap[DateTime, String, IllegalArgumentException](DateTime.parse)(_.toString)
}
