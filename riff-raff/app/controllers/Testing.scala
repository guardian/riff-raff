package controllers

import play.api.mvc.Controller
import magenta._
import collection.mutable.ArrayBuffer
import magenta.CommandOutput
import magenta.Info
import magenta.CommandError
import magenta.FinishContext
import magenta.DeployParameters
import magenta.StartContext
import magenta.TaskRun
import magenta.Verbose
import magenta.KeyRing
import magenta.MessageStack
import magenta.Deploy
import magenta.Deployer
import magenta.Stage
import magenta.Build
import deployment.{DeployV2Record, Task, DeployRecord}
import java.util.UUID
import tasks.Task
import play.api.data.Form
import play.api.data.Forms._
import org.joda.time.DateTime
import persistence.{DocumentConverter, DocumentStoreConverter, RecordConverter, Persistence}

case class SimpleDeployDetail(uuid: UUID, time: DateTime)

object Testing extends Controller with Logging {
  def reportTestPartial(take: Int, verbose: Boolean) = NonAuthAction { implicit request =>
    val logUUID = UUID.randomUUID()
    val parameters = DeployParameters(Deployer("Simon Hildrew"), Build("tools::deploy", "131"), Stage("DEV"), DefaultRecipe())

    val testTask1 = new Task {
      def execute(sshCredentials: KeyRing) {}
      def description = "Test task that does stuff, the first time"
      def verbose = "A particularly verbose task description that lists some stuff, innit"
    }
    val testTask2 = new Task {
      def execute(sshCredentials: KeyRing) {}
      def description = "Test task that does stuff"
      def verbose = "A particularly verbose task description that lists some stuff, innit"
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
      wrapper(Some(message.deployUUID), UUID.randomUUID(), Verbose("Downloading from http://teamcity.gudev.gnl:8111/guestAuth/repository/download/tools%3A%3Adeploy/131/artifacts.zip to /var/folders/ZO/ZOSa3fR3FsCiU3jxetWKQU+++TQ/-Tmp-/sbt_5489e15..."),message.deploy),
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


    val report = DeployV2Record(new DateTime(), Task.Deploy, logUUID, parameters, input.toList.take(take))

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



  def uuidList = AuthAction { implicit request =>
    val v1Set = Persistence.store.getDeployUUIDs.toSet
    val v2Set = Persistence.store.getDeployV2UUIDs().toSet
    val allDeploys = (v1Set ++ v2Set).toSeq.sortBy(_.time.getMillis).reverse
    Ok(views.html.test.uuidList(request, allDeploys, v1Set, v2Set))
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
          case "deleteV1" => {
            log.info("Deleting deploy in V1 with UUID %s" format form.uuid)
            Persistence.store.deleteDeployLog(UUID.fromString(form.uuid))
            Redirect(routes.Testing.uuidList())
          }
          case "deleteV2" => {
            log.info("Deleting deploy in V2 with UUID %s" format form.uuid)
            Persistence.store.deleteDeployLogV2(UUID.fromString(form.uuid))
            Redirect(routes.Testing.uuidList())
          }
          case "migrate" => {
            log.info("Migrating deploy with UUID %s" format form.uuid)
            val deployRecord = Persistence.store.getDeploy(UUID.fromString(form.uuid))
            deployRecord.foreach{ deploy =>
              val conversion = RecordConverter(deploy)
              Persistence.store.writeDeploy(conversion.deployDocument)
            }
            Redirect(routes.Testing.uuidList())
          }
        }
      }
    )
  }

  def migrateAllV1 = AuthAction { implicit request =>
    val v1Set = Persistence.store.getDeployUUIDs.toSet
    val v2Set = Persistence.store.getDeployV2UUIDs().toSet
    val v1Only = v1Set -- v2Set
    v1Only.foreach { deployToMigrate =>
      val deployRecord = Persistence.store.getDeploy(deployToMigrate.uuid)
      deployRecord.foreach{ deploy =>
        val conversion = RecordConverter(deploy)
        Persistence.store.writeDeploy(conversion.deployDocument)
      }
    }
    Redirect(routes.Testing.uuidList())
  }

  def viewUUIDv1(uuid: String, verbose: Boolean) = AuthAction { implicit request =>
    val record = Persistence.store.getDeploy(UUID.fromString(uuid)).get
    Ok(views.html.deploy.viewDeploy(request, record, verbose))
  }

}
