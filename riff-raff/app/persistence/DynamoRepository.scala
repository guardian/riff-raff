package persistence

import java.util.UUID

import org.scanamo.ops._
import org.scanamo.{DynamoFormat, Scanamo}
import conf.Config
import org.joda.time.DateTime

abstract class DynamoRepository(config: Config) {

  val client = config.dynamoDb.client
  def exec[A](ops: ScanamoOps[A]): A = Scanamo(client).exec(ops)
  def tablePrefix: String

  // TODO set up Dynamo local
  val stage = if (config.stage == "DEV") "CODE" else config.stage
  lazy val tableName = s"$tablePrefix-$stage"

  implicit val uuidFormat: DynamoFormat[UUID] =
    DynamoFormat.coercedXmap[UUID, String, IllegalArgumentException](
      UUID.fromString,
      _.toString
    )

  implicit val jodaStringFormat: DynamoFormat[DateTime] =
    DynamoFormat.coercedXmap[DateTime, String, IllegalArgumentException](
      DateTime.parse,
      _.toString
    )
}
