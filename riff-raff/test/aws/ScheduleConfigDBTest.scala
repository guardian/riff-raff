package aws

import awscala.dynamodbv2._
import org.scalatest.FreeSpec

class ScheduleConfigDBTest extends FreeSpec {

  "test db stuff - use this to test DynamoDB stuff locally during development" - {
    implicit val dynamoDB: DynamoDB = DynamoDB.local()

    "create database table" ignore {
      createTable()
    }

    "destroy table" ignore {
      destroyTable()
    }
  }


  /**
    * NB: Only use this for local testing and running in DEV mode!
    */
  private[aws] def createTable()(implicit dynamoDB: DynamoDB) = {
    val scheduleConfigTable = Table(
      name = ScheduleConfigDB.tableName,
      hashPK = "id",
      rangePK = Some("lastEdited"),
      attributes = Seq(
        AttributeDefinition("id", AttributeType.String),
        AttributeDefinition("lastEdited", AttributeType.Number)
      ),
      globalSecondaryIndexes = Seq(
        GlobalSecondaryIndex(
          name = "ScheduleConfig",
          keySchema = Seq(KeySchema("id", KeyType.Hash), KeySchema("lastEdited", KeyType.Range)),
          projection = Projection(ProjectionType.All),
          provisionedThroughput = ProvisionedThroughput(15, 15)
        )
      ),
      provisionedThroughput = Some(ProvisionedThroughput(15, 15))
    )
    dynamoDB.createTable(scheduleConfigTable)
  }

  private[aws] def destroyTable()(implicit dynamoDB: DynamoDB): Unit = {
    ScheduleConfigDB.getTable().destroy()
  }
}
