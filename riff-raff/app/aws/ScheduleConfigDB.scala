package aws

import awscala.dynamodbv2.{DynamoDB, Table}

object ScheduleConfigDB {

  val tableName = "schedule-config-DEV"

  def getTable()(implicit dynamoDB: DynamoDB): Table = dynamoDB.table(tableName).get

}