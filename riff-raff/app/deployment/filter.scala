package deployment

import java.net.URLEncoder
import play.api.mvc.{Call, RequestHeader}
import magenta.RunState

trait QueryStringBuilder {
  def queryStringParams: List[(String,String)]
  def queryString = queryStringParams.map {
    case (k, v) => k + "=" + URLEncoder.encode(v, "UTF-8")
  }.mkString("&")
  def q = queryString
}

case class DeployFilter(
  projectName: Option[String] = None,
  stage: Option[String] = None,
  deployer: Option[String] = None,
  status: Option[RunState.Value] = None,
  task: Option[TaskType.Value] = None ) extends QueryStringBuilder {

  lazy val queryStringParams: List[(String, String)] = {
    Nil ++
      projectName.map("projectName" -> _.toString) ++
      stage.map("stage" -> _.toString) ++
      deployer.map("deployer" -> _.toString) ++
      status.map("status" -> _.toString) ++
      task.map("task" -> _.toString)
  }

  def withProjectName(projectName: Option[String]) = this.copy(projectName=projectName)
  def withStage(stage: Option[String]) = this.copy(stage=stage)
  def withDeployer(deployer: Option[String]) = this.copy(deployer=deployer)
  def withStatus(status: Option[RunState.Value]) = this.copy(status=status)
  def withTask(task: Option[TaskType.Value]) = this.copy(task=task)

  lazy val default = this == DeployFilter()
}

object DeployFilter {
  def fromRequest(implicit r: RequestHeader):Option[DeployFilter] = {
    def param(s: String): Option[String] =
      r.queryString.get(s).flatMap(_.headOption).filter(!_.isEmpty)

    def listParam(s: String): List[String] =
      r.queryString.get(s).getOrElse(Nil).flatMap(_.split(",").map(_.trim).filter(!_.isEmpty)).toList

    val statusType = try {
      param("status").map(RunState.withName)
    } catch { case t:Throwable => throw new IllegalArgumentException("Unknown value for status parameter")}

    val taskType = try {
      param("task").map(TaskType.withName)
    } catch { case t:Throwable => throw new IllegalArgumentException("Unknown value for task parameter")}

    val filter = DeployFilter(
      projectName = param("projectName"),
      stage = param("stage"),
      deployer = param("deployer"),
      status = statusType,
      task = taskType
    )

    if (filter == DeployFilter()) None else Some(filter)
  }
}

trait Pagination extends QueryStringBuilder {
  def page: Int
  def pageSize: Int
  def itemCount: Option[Int]
  def withPage(page: Int): Pagination

  implicit def call2AddQueryParams(call: Call) = new {
    def appendQueryParams(params: String): Call = {
      val sep = if (call.url.contains("?")) "&" else "?"
      Call(call.method, "%s%s%s" format (call.url, sep, params))
    }
  }

  val DISABLED = Call("GET","#")

  def pageList: List[Int] = (lowerBound to upperBound).toList
  def lowerBound: Int = math.max(1, pageCount.map(pageCount => math.min(page-2,pageCount-4)).getOrElse(page-4))
  def upperBound: Int = pageCount.map(pageCount => math.min(pageCount, math.max(page+2,5))).getOrElse(page)

  def pageCount: Option[Int] = itemCount.map(itemCount => math.ceil(itemCount.toDouble / pageSize).toInt)

  def hasPrevious = page != 1
  def hasNext = true
  def hasLast = pageCount.isDefined

  def previous(base: Call) = if (hasPrevious) base.appendQueryParams(withPage(page - 1).q) else DISABLED
  def next(base: Call) = if (hasNext) base.appendQueryParams(withPage(page + 1).q) else DISABLED

  def first(base: Call) = toPage(base, 1)
  def last(base: Call) = pageCount.map(toPage(base, _)).getOrElse(DISABLED)

  def toPage(base: Call, newPage: Int) = base.appendQueryParams(withPage(newPage).q)
}

case class PaginationView(
  pageSize: Option[Int] = PaginationView.DEFAULT_PAGESIZE,
  page: Int = PaginationView.DEFAULT_PAGE
) extends QueryStringBuilder {
  def isDefault = pageSize == PaginationView.DEFAULT_PAGESIZE && page == PaginationView.DEFAULT_PAGE

  lazy val queryStringParams: List[(String, String)] =
    Nil ++
      pageSize.map("pageSize" -> _.toString) ++
      Some("page" -> page.toString)

  lazy val skip = pageSize.map(_*(page-1))

  def withPageSize(pageSize: Option[Int]) = this.copy(pageSize=pageSize)
  def withPage(page: Int): PaginationView = this.copy(page=page)
}

object PaginationView {
  val DEFAULT_PAGESIZE = Some(20)
  val DEFAULT_PAGE = 1

  def fromRequest(implicit r: RequestHeader):PaginationView = {
    def param(s: String): Option[String] =
      r.queryString.get(s).flatMap(_.headOption).filter(!_.isEmpty)

    PaginationView(
      pageSize = param("pageSize").map(_.toInt).orElse(DEFAULT_PAGESIZE),
      page = param("page").map(_.toInt).getOrElse(DEFAULT_PAGE)
    )
  }
}

case class DeployFilterPagination(filter: DeployFilter, pagination: PaginationView, itemCount:Option[Int] = None) extends QueryStringBuilder with Pagination {
  lazy val queryStringParams = filter.queryStringParams ++ pagination.queryStringParams

  def replaceFilter(f: DeployFilter => DeployFilter) = this.copy(filter=f(filter), pagination=pagination.withPage(1))
  def replacePagination(f: PaginationView => PaginationView) = this.copy(pagination=f(pagination))

  def withProjectName(projectName: Option[String]) = this.copy(filter=filter.withProjectName(projectName))
  def withStage(stage: Option[String]) = this.copy(filter=filter.withStage(stage))
  def withDeployer(deployer: Option[String]) = this.copy(filter=filter.withDeployer(deployer))
  def withStatus(status: Option[RunState.Value]) = this.copy(filter=filter.withStatus(status))
  def withTask(task: Option[TaskType.Value]) = this.copy(filter=filter.withTask(task))
  def withPage(page: Int): DeployFilterPagination = this.copy(pagination=pagination.withPage(page))
  def withPageSize(size: Option[Int]) = this.copy(pagination=pagination.withPageSize(size))

  def withItemCount(count: Option[Int]) = this.copy(itemCount = count)

  val page = pagination.page
  val pageSize = pagination.pageSize.getOrElse(1)

}

object DeployFilterPagination {
  def fromRequest(implicit r: RequestHeader):DeployFilterPagination = {
    DeployFilterPagination(DeployFilter.fromRequest.getOrElse(DeployFilter()), PaginationView.fromRequest)
  }
}