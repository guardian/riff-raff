package migration
package data

import java.util.UUID
import java.time.Instant

/*
 * table ApiKey 
 *   ( id           char(32)     primary key
 *   , content      jsonb        not null
 *   )
 */

final case class ApiKey(
  key           : String,
  application   : String,
  issuedBy      : String,
  created       : Instant,
  lastUsed      : Option[Instant],
  callCounters  : Map[String, Long]
)

/*
 * table Auth 
 *   ( email        varchar(100) primary key
 *   , content      jsonb        not null
 *   )
 */

final case class Auth(
  email         : String, 
  approvedBy    : String, 
  approvedDate  : Instant
)

/*
 * table Deploy
 *   ( id        uuid   primary key
 *   , content   jsonb  not null
 *   )
 */

final case class Deploy(
  id               : UUID,
  stringUUID       : Option[String],
  startTime        : Instant,
  parameters       : Parameters,
  status           : RunState,
  summarised       : Option[Boolean],
  totalTasks       : Option[Int],
  completedTasks   : Option[Int],
  lastActivityTime : Option[Instant],
  hasWarnings      : Option[Boolean]
)

final case class Parameters(
  deployer         : String,
  projectName      : String,
  buildId          : String,
  stage            : String,
  tags             : Map[String,String],
  selector         : DeploymentSelector
)

sealed abstract class RunState
object RunState {
  case object NotRunning extends RunState
  case object Completed extends RunState
  case object Running extends RunState
  case object ChildRunning extends RunState
  case object Failed extends RunState
}

sealed abstract class DeploymentSelector
case object AllSelector extends DeploymentSelector
final case class KeysSelector(ids: List[DeploymentKey]) extends DeploymentSelector

final case class DeploymentKey(
  name             : String, 
  action           : String, 
  stack            : String, 
  region           : String
)

/*
 * table Log
 *   ( deploy   uuid   references Deploy (id) not null
 *   , content  jsonb  not null
 *   )
 */

final case class Log(
  deploy           : UUID,
  id               : UUID,
  parent           : Option[UUID],
  document         : Document,
  time             : Instant
)

sealed abstract class Document
final case class TaskListDocument(taskList: List[TaskDetail]) extends Document
final case class TaskRunDocument(task: TaskDetail) extends Document
final case class InfoDocument(text: String) extends Document
final case class CommandOutputDocument(text: String) extends Document
final case class CommandErrorDocument(text: String) extends Document
final case class VerboseDocument(text: String) extends Document
final case class WarningDocument(text: String) extends Document
final case class FailDocument(text: String, detail: ThrowableDetail) extends Document
case object DeployDocument extends Document
case object FinishContextDocument extends Document
case object FailContextDocument extends Document

final case class TaskDetail(name: String, description: String, verbose: String)
final case class ThrowableDetail(
  name          : String, 
  message       : String , 
  stackTrace    : String, 
  cause         : Option[ThrowableDetail]
)

