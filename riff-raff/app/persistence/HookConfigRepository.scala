package persistence

import java.util.UUID

import org.scanamo.{DynamoFormat, Table}
import notification.{HookConfig, HttpMethod}
import cats.syntax.either._
import conf.Config
import org.scanamo.generic.auto._

class HookConfigRepository(config: Config) extends DynamoRepository(config) {

  implicit val httpMethodFormat =
    DynamoFormat.coercedXmap[HttpMethod, String, NoSuchElementException](
      HttpMethod.apply,
      _.toString
    )

  override val tablePrefix = "hook-config"

  val table = Table[HookConfig](tableName)

  import org.scanamo.syntax._

  def getPostDeployHook(id: UUID): Option[HookConfig] =
    exec(table.get("id" -> id)).flatMap(_.toOption)
  def getPostDeployHook(projectName: String, stage: String): Seq[HookConfig] =
    exec(table.index("hook-config-project").query("projectName" -> projectName))
      .flatMap(_.toOption)
      .filter(_.stage == stage)
  def getPostDeployHookList: Iterable[HookConfig] =
    exec(table.scan()).flatMap(_.toOption)
  def setPostDeployHook(config: HookConfig): Unit = exec(table.put(config))
  def deletePostDeployHook(id: UUID): Unit = exec(table.delete("id" -> id))
}
