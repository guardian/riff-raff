package persistence

import java.util.UUID

import ci.{ContinuousDeploymentConfig, Trigger}
import com.gu.scanamo.ops.ScanamoOps
import com.gu.scanamo.syntax._
import com.gu.scanamo.{DynamoFormat, Scanamo, Table}
import conf.Configuration
import org.joda.time.DateTime

object ContinuousDeploymentConfigRepository {
  val client = Configuration.continuousDeployment.dynamoClient
  val stage = Configuration.stage

  implicit val uuidFormat =
    DynamoFormat.coercedXmap[UUID, String, IllegalArgumentException](UUID.fromString)(_.toString)

  implicit val jodaStringFormat =
    DynamoFormat.coercedXmap[DateTime, String, IllegalArgumentException](DateTime.parse)(_.toString)

  implicit val triggerModeFormat =
    DynamoFormat.coercedXmap[Trigger.Mode, String, NoSuchElementException](Trigger.withName)(_.toString)

  val table = Table[ContinuousDeploymentConfig](s"continuous-deployment-config-$stage")
  def exec[A](ops: ScanamoOps[A]): A = Scanamo.exec(client)(ops)

  def getContinuousDeploymentList(): List[ContinuousDeploymentConfig] =
    exec(table.scan()).flatMap(_.toOption)

  def getContinuousDeployment(id: UUID): Option[ContinuousDeploymentConfig] =
    exec(table.get('id -> id)).flatMap(_.toOption)

  def setContinuousDeployment(cd: ContinuousDeploymentConfig): Unit =
    exec(table.put(cd))

  def deleteContinuousDeployment(id: UUID): Unit =
    exec(table.delete('id -> id))

}
