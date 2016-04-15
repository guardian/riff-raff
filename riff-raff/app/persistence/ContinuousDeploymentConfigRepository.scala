package persistence

import java.util.UUID

import cats.data.Validated
import ci.{ContinuousDeploymentConfig, Trigger}
import com.gu.scanamo.{DynamoFormat, Scanamo, TypeCoercionError}
import com.gu.scanamo.syntax._
import conf.Configuration
import org.joda.time.DateTime

object ContinuousDeploymentConfigRepository {
  val tableName = "continuous-deployment-config"
  val client = Configuration.continuousDeployment.dynamoClient

  implicit val uuidFormat = DynamoFormat.xmap[UUID, String](
    s => Validated.catchOnly[IllegalArgumentException](UUID.fromString(s)).leftMap(TypeCoercionError(_)).toValidatedNel
  )(_.toString)

  implicit val jodaStringFormat = DynamoFormat.xmap[DateTime, String](
    s => Validated.catchOnly[IllegalArgumentException](DateTime.parse(s)).leftMap(TypeCoercionError(_)).toValidatedNel
  )(_.toString)

  implicit val triggerModeFormat = DynamoFormat.xmap[Trigger.Mode, String](
    s => Validated.catchOnly[NoSuchElementException](Trigger.withName(s)).leftMap(TypeCoercionError(_)).toValidatedNel
  )(_.toString)

  def getContinuousDeploymentList(): Iterable[ContinuousDeploymentConfig] =
    Scanamo.scan[ContinuousDeploymentConfig](client)(tableName).toList.flatMap(_.toOption)

  def getContinuousDeployment(id: UUID): Option[ContinuousDeploymentConfig] =
    Scanamo.get[ContinuousDeploymentConfig](client)(tableName)('id -> id).flatMap(_.toOption)

  def setContinuousDeployment(cd: ContinuousDeploymentConfig): Unit = {
    Scanamo.put(client)(tableName)(cd)
  }
  def deleteContinuousDeployment(id: UUID): Unit = {
    Scanamo.delete(client)(tableName)('id -> id)
  }
}
