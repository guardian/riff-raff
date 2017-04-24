package persistence

import java.util.UUID

import com.gu.scanamo.{DynamoFormat, Table}
import notification.{HookConfig, HttpMethod}

object HookConfigRepository extends DynamoRepository {

  implicit val httpMethodFormat =
    DynamoFormat.coercedXmap[HttpMethod, String, NoSuchElementException](HttpMethod.apply)(_.toString)

  override val tablePrefix = "hook-config"

  val table = Table[HookConfig](tableName)

  import com.gu.scanamo.syntax._

  def getPostDeployHook(id: UUID): Option[HookConfig] = exec(table.get('id -> id)).flatMap(_.toOption)
  def getPostDeployHook(projectName: String, stage: String): Seq[HookConfig] =
    exec(table.index("hook-config-project").query('projectName -> projectName))
      .flatMap(_.toOption)
      .filter(_.stage == stage)
  def getPostDeployHookList: Iterable[HookConfig] = exec(table.scan()).flatMap(_.toOption)
  def setPostDeployHook(config: HookConfig): Unit = exec(table.put(config))
  def deletePostDeployHook(id: UUID): Unit = exec(table.delete('id -> id))
}
