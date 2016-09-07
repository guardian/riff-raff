package persistence

import java.util.UUID

import ci.{ContinuousDeploymentConfig, Trigger}
import com.gu.scanamo.syntax._
import com.gu.scanamo.{DynamoFormat, Table}

object ContinuousDeploymentConfigRepository extends DynamoRepository {

  implicit val triggerModeFormat =
    DynamoFormat.coercedXmap[Trigger.Mode, String, NoSuchElementException](Trigger.withName)(_.toString)

  override val tablePrefix = "continuous-deployment-config"

  val table = Table[ContinuousDeploymentConfig](tableName)

  def getContinuousDeploymentList(): List[ContinuousDeploymentConfig] =
    exec(table.scan()).flatMap(_.toOption)

  def getContinuousDeployment(id: UUID): Option[ContinuousDeploymentConfig] =
    exec(table.get('id -> id)).flatMap(_.toOption)

  def setContinuousDeployment(cd: ContinuousDeploymentConfig): Unit =
    exec(table.put(cd))

  def deleteContinuousDeployment(id: UUID): Unit =
    exec(table.delete('id -> id))
}
