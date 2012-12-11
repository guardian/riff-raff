package deployment

import java.net.URLEncoder
import play.api.mvc.RequestHeader
import magenta.RunState

case class DeployFilter(
  projectName: Option[String] = None,
  stage: Option[String] = None,
  deployer: Option[String] = None,
  status: Option[RunState.Value] = None,
  task: Option[Task.Value] = None ) {

  lazy val queryStringParams: List[(String, String)] = {
    Nil ++
      projectName.map("projectName" -> _.toString) ++
      stage.map("stage" -> _.toString) ++
      deployer.map("deployer" -> _.toString) ++
      status.map("status" -> _.toString) ++
      task.map("task" -> _.toString)
  }
  lazy val queryString = queryStringParams.map {
    case (k, v) => k + "=" + URLEncoder.encode(v, "UTF-8")
  }.mkString("&")

  def q = queryString
}

object DeployFilter {
  def fromRequest(r: RequestHeader):Option[DeployFilter] = {
    def param(s: String): Option[String] =
      r.queryString.get(s).flatMap(_.headOption).filter(!_.isEmpty)

    def listParam(s: String): List[String] =
      r.queryString.get(s).getOrElse(Nil).flatMap(_.split(",").map(_.trim).filter(!_.isEmpty)).toList

    val filter = DeployFilter(
      projectName = param("projectName"),
      stage = param("stage"),
      deployer = param("deployer"),
      status = param("status").map(RunState.withName),
      task = param("task").map(Task.withName)
    )

    if (filter == DeployFilter()) None else Some(filter)
  }
}
