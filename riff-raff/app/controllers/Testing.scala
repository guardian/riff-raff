package controllers

import org.joda.time.format.{ISODateTimeFormat, DateTimeFormat}
import play.api.mvc.{Action, Controller}
import magenta._
import resources.LookupSelector
import collection.mutable.ArrayBuffer
import deployment.{PaginationView, DeployFilter, DeployRecord}
import java.util.UUID
import tasks.Task
import play.api.data.Form
import play.api.data.Forms._
import org.joda.time.{Duration, Interval, DateTime}
import persistence.{TaskRunDocument, DocumentStoreConverter, Persistence}

case class SimpleDeployDetail(uuid: UUID, time: Option[DateTime])

object Testing extends Controller with Logging with LoginActions {
  def reportTestPartial(take: Int, verbose: Boolean) = Action { implicit request =>
    val logUUID = UUID.randomUUID()
    val parameters = DeployParameters(Deployer("Simon Hildrew"), Build("tools::deploy", "131"), Stage("DEV"), DefaultRecipe())

    val testTask1 = new Task {
      override def execute(logger: DeployLogger, stopFlag: => Boolean) {}
      def description = "Test task that does stuff, the first time"
      def verbose = "A particularly verbose task description that lists some stuff, innit"

      def keyRing = ???
    }
    val testTask2 = new Task {
      override def execute(logger: DeployLogger, stopFlag: => Boolean) {}
      def description = "Test task that does stuff"
      def verbose = "A particularly verbose task description that lists some stuff, innit"

      def keyRing = ???
    }



    def wrapper(parent: Option[UUID], id: UUID, messages: Message*): MessageWrapper = {
      MessageWrapper(MessageContext(logUUID, parameters, parent), id, MessageStack(messages.toList))
    }

    object message {
      val deploy = Deploy(parameters)
      val deployUUID = UUID.randomUUID()
      val task1 = TaskRun(testTask1)
      val task1UUID = UUID.randomUUID()
      val task2 = TaskRun(testTask2)
      val task2UUID = UUID.randomUUID()
      val info = Info("$ command line action")
      val infoUUID = UUID.randomUUID()
    }

    val input = ArrayBuffer(
      wrapper(None, message.deployUUID, StartContext(message.deploy)),
      wrapper(Some(message.deployUUID), UUID.randomUUID(), Info("Downloading artifact"),message.deploy),
      wrapper(Some(message.deployUUID), UUID.randomUUID(), Verbose("Downloading from http://teamcity.guprod.gnm:80/guestAuth/repository/download/tools%3A%3Adeploy/131/artifacts.zip to /var/folders/ZO/ZOSa3fR3FsCiU3jxetWKQU+++TQ/-Tmp-/sbt_5489e15..."),message.deploy),
      wrapper(Some(message.deployUUID), UUID.randomUUID(), Verbose("http: teamcity.gudev.gnl GET /guestAuth/repository/download/tools%3A%3Adeploy/131/artifacts.zip HTTP/1.1"), message.deploy),
      wrapper(Some(message.deployUUID), UUID.randomUUID(), Verbose("""downloaded:
      /var/folders/ZO/ZOSa3fR3FsCiU3jxetWKQU+++TQ/-Tmp-/sbt_5489e15/deploy.json
    /var/folders/ZO/ZOSa3fR3FsCiU3jxetWKQU+++TQ/-Tmp-/sbt_5489e15/packages/riff-raff/riff-raff.jar"""), message.deploy),
      wrapper(Some(message.deployUUID), UUID.randomUUID(), Info("Reading deploy.json"), message.deploy),
      wrapper(Some(message.deployUUID), message.task1UUID, StartContext(message.task1), message.deploy),
      wrapper(Some(message.task1UUID), UUID.randomUUID(), FinishContext(message.task1), message.task1, message.deploy),
      wrapper(Some(message.deployUUID), message.task2UUID, StartContext(message.task2), message.deploy),
      wrapper(Some(message.task2UUID), message.infoUUID, StartContext(message.info), message.task2, message.deploy),

      wrapper(Some(message.infoUUID), UUID.randomUUID(), CommandOutput("Some command output from command line action"), message.info, message.task2, message.deploy),
      wrapper(Some(message.infoUUID), UUID.randomUUID(), CommandError("Some command error from command line action"), message.info, message.task2, message.deploy),
      wrapper(Some(message.infoUUID), UUID.randomUUID(), CommandOutput("Some more command output from command line action"), message.info, message.task2, message.deploy)
    )


    val report = DeployRecord(new DateTime(), logUUID, parameters, messages=input.toList.take(take))

    Ok(views.html.test.reportTest(request,report,verbose))
  }

  case class TestForm(project:String, action:String, hosts: List[String])

  lazy val testForm = Form[TestForm](
    mapping(
      "project" -> text,
      "action" -> nonEmptyText,
      "hosts" -> list(text)
    )(TestForm.apply)
      (TestForm.unapply)
  )

  def hosts = AuthAction { Ok(s"Deploy Info hosts:\n${LookupSelector().hosts.all.map(h => s"${h.name} - ${h.tags.get("group").getOrElse("n/a")}").mkString("\n")}") }

  def form =
    AuthAction { implicit request =>
      Ok(views.html.test.form(request, testForm))
    }

  def formPost =
    AuthAction { implicit request =>
      testForm.bindFromRequest().fold(
        errors => BadRequest(views.html.test.form(request,errors)),
        form => {
          log.info("Form post: %s" format form.toString)
          Redirect(routes.Testing.form)
        }
      )
    }

  def testcharset = AuthAction { implicit request =>
    Ok("Raw string: %s\nParsed strings: \n%s" format (request.rawQueryString, request.queryString))
  }

  def uuidList(limit:Int) = AuthAction { implicit request =>
    val allDeploys = Persistence.store.getDeployUUIDs().toSeq.sortBy(_.time.map(_.getMillis).getOrElse(Long.MaxValue)).reverse
    Ok(views.html.test.uuidList(request, allDeploys.take(limit)))
  }

  def S3LatencyList(limit:Int, csv: Boolean) = AuthAction { implicit request =>
    val filter = DeployFilter.fromRequest
    val pagination = PaginationView.fromRequest
    val allDeploys = DocumentStoreConverter.getDeployList(filter, pagination, fetchLog = true)
    val times = allDeploys.map { deploy =>
      val taskRunLines = deploy.messages.flatMap { message =>
        message.stack.top match {
          case StartContext(TaskRun(detail)) => Some(message -> detail)
          case _ => None
        }
      }
      log.info(s"got ${taskRunLines.length} lines")
      val s3UploadTasks = taskRunLines.dropWhile(!_._2.name.contains("S3Upload"))
      val endOfS3UploadTasks = s3UploadTasks.dropWhile(_._2.name.contains("S3Upload"))
      val s3Start = s3UploadTasks.headOption.map(_._1.stack.time)
      val s3End = endOfS3UploadTasks.headOption.map(_._1.stack.time)
      deploy -> s3Start.zip(s3End).map{ case (start, end) => new Duration(start,end) }.headOption
    }
    if (csv) {
      val formatter = DateTimeFormat.forPattern("dd/MM/yyyy HH:mm:ss")
      val csvLines = s"Time,Project,S3 Duration" +:
        times.map{case(deploy, duration) =>
          s"${formatter.print(deploy.time)},${deploy.parameters.build.projectName},${duration.map(_.getStandardSeconds).getOrElse("")}"
        }
      val csvCall = routes.Testing.S3LatencyList(limit, true)
      Ok(csvLines.mkString("\n")).as("text/csv").withHeaders("Content-Disposition" -> "attachment; filename=s3Latencies.csv")
    }
    else
      Ok(views.html.test.s3Latencies(request, times))
  }

  def debugLogViewer(uuid: String) = AuthAction { implicit request =>
    val deploy = DocumentStoreConverter.getDeploy(UUID.fromString(uuid))
    deploy.map(deploy => Ok(views.html.test.debugLogViewer(request, deploy))).getOrElse(NotFound("Can't find document with that UUID"))
  }

  case class UuidForm(uuid:String, action:String)

  lazy val uuidForm = Form[UuidForm](
    mapping(
      "uuid" -> text(36,36),
      "action" -> nonEmptyText
    )(UuidForm.apply)
      (UuidForm.unapply)
  )

  def actionUUID = AuthAction { implicit request =>
    uuidForm.bindFromRequest().fold(
      errors => Redirect(routes.Testing.uuidList()),
      form => {
        form.action match {
          case "summarise" => {
            log.info("Summarising deploy with UUID %s" format form.uuid)
            Persistence.store.summariseDeploy(UUID.fromString(form.uuid))
            Redirect(routes.Testing.uuidList())
          }
          case "deleteV2" => {
            log.info("Deleting deploy in V2 with UUID %s" format form.uuid)
            Persistence.store.deleteDeployLog(UUID.fromString(form.uuid))
            Redirect(routes.Testing.uuidList())
          }
          case "addStringUUID" => {
            log.info("Adding string UUID for %s" format form.uuid)
            Persistence.store.addStringUUID(UUID.fromString(form.uuid))
            Redirect(routes.Testing.uuidList())
          }
        }
      }
    )
  }

  def transferAllUUIDs = AuthAction { implicit request =>
    val allDeploys = Persistence.store.getDeployUUIDsWithoutStringUUIDs
    allDeploys.foreach(deploy => Persistence.store.addStringUUID(deploy.uuid))
    Redirect(routes.Testing.uuidList())
  }

}
