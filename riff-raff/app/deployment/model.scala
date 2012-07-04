package deployment

import magenta.tasks.Task
import controllers.{Logging, routes}

case class DeployParameters(project:String, build:Int, stage:String)

object Stages {
  lazy val list = List("CODE","QA","RELEASE","PROD","STAGE","TEST","INFRA").sorted
}

trait Status {
  def imageCode: String
}
object NotStarted extends Status { val imageCode = "" }
object Running extends Status { val imageCode = """<img src="%s"/>""".format(routes.Assets.at("images/ajax-loader.gif")) }
object Error extends Status { val imageCode = "" }
object Done extends Status { val imageCode = "<i class=\"icon-ok\"></i>" }

case class DeployLog(messages: Seq[LogData], finished: Boolean) {
  lazy val showLoader = !finished
  lazy val lines = messages map(_.render)
}

trait LogData {
  def render: String
}

case class LogString(s: String) extends LogData {
  lazy val render = s.replace("\n","</br>")
}

class TaskStatus extends LogData with Logging {
  private var tasks: List[Task] = Nil
  private var taskStatusMap = Map.empty[Task, Status].withDefaultValue(NotStarted)
  private var taskLogLines = Map.empty[Task, List[String]].withDefaultValue(Nil)
  def addTasks(newTasks: List[Task]) {tasks = newTasks}
  def updateStatus(task: Task, state: Status) { taskStatusMap += (task -> state) }
  def status(task: Task) = taskStatusMap(task)
  def run[T](task: Task)(block: => T) {
    updateStatus(task,Running)
    try {
      block
    } catch {
      case e =>
        logToTask(task,e.toString)
        logToTask(task,e.getStackTraceString)
        updateStatus(task, Error)
        throw e
    }
    updateStatus(task,Done)
  }
  def runningTask = {
    val task = taskStatusMap.filter{ tuple => log.info(tuple._2.toString); tuple._2 == Running}.keys.headOption
    log.debug("running task is %s" format task.toString)
    task
  }
  def logToTask(task: Task, s:String) {
    val logLines = taskLogLines(task)
    taskLogLines = taskLogLines + (task -> (s :: logLines))
  }
  override def render(): String = {
    tasks.map{ task =>
      val status = taskStatusMap(task)
      val logLines = taskLogLines(task)
      views.html.snippets.task(task, status, logLines.reverse)
    } mkString("\n")
  }
}
