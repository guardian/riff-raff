package deployment

import java.util.UUID

import akka.actor.ActorSystem
import akka.agent.Agent
import akka.util.Switch
import ci._
import com.gu.management.DefaultSwitch
import controllers.Logging
import lifecycle.Lifecycle
import magenta._
import org.joda.time.DateTime
import persistence.{DocumentStoreConverter, RestrictionConfigDynamoRepository}
import restrictions.RestrictionChecker
import rx.lang.scala.{Observable, Subject, Subscription}
import utils.VCSInfo

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal

class Deployments(deploymentEngine: DeploymentEngine,
                  builds: Builds,
                  documentStoreConverter: DocumentStoreConverter,
                  restrictionConfigDynamoRepository: RestrictionConfigDynamoRepository)
                 (implicit val executionContext: ExecutionContext)
  extends Lifecycle with Logging {

  def deploy(requestedParams: DeployParameters, requestSource: RequestSource): Either[Error, UUID] = {
    log.info(s"Started deploying $requestedParams")
    val restrictionsPreventingDeploy = RestrictionChecker.configsThatPreventDeployment(restrictionConfigDynamoRepository,
      requestedParams.build.projectName, requestedParams.stage.name, requestSource)
    if (restrictionsPreventingDeploy.nonEmpty) {
      Left(Error(s"Unable to queue deploy as restrictions are currently in place: ${restrictionsPreventingDeploy.map(r => s"${r.fullName}: ${r.note}").mkString("; ")}"))
    } else if (enableQueueingSwitch.isSwitchedOff) {
      Left(Error(s"Unable to queue a new deploy; deploys are currently disabled by the ${enableQueueingSwitch.name} switch"))
    } else {
      val params = if (requestedParams.build.id != "lastSuccessful")
        requestedParams
      else {
        builds.getLastSuccessful(requestedParams.build.projectName).map { latestId =>
          requestedParams.copy(build = requestedParams.build.copy(id=latestId))
        }.getOrElse(requestedParams)
      }
      deploysEnabled.whileOnYield {
        val record = create(params)
        deploymentEngine.interruptibleDeploy(record)
        Right(record.uuid)
      } getOrElse {
        Left(Error(s"Unable to queue a new deploy; deploys are currently disabled by the ${enableDeploysSwitch.name} switch"))
      }
    }
  }

  def stop(uuid: UUID, fullName: String) {
    deploymentEngine.stopDeploy(uuid, fullName)
  }

  def getStopFlag(uuid: UUID): Boolean = deploymentEngine.getDeployStopFlag(uuid)

  def create(params: DeployParameters): Record = {
    log.info(s"Creating deploy record for $params")
    val uuid = java.util.UUID.randomUUID()
    val hostNameMetadata = Map(Record.RIFFRAFF_HOSTNAME -> java.net.InetAddress.getLocalHost.getHostName)
    val record = deployRecordFor(uuid, params) ++ hostNameMetadata
    library send { _ + (uuid -> Agent(record)) }
    documentStoreConverter.saveDeploy(record)
    await(uuid)
  }

  def deployRecordFor(uuid: UUID, parameters: DeployParameters): DeployRecord = {
    val build = builds.all.find(b => b.jobName == parameters.build.projectName && b.id.toString == parameters.build.id)
    val metaData = build.map { case b: S3Build =>
      Map(
        "branch" -> b.branchName,
        VCSInfo.REVISION -> b.revision,
        VCSInfo.CIURL -> b.vcsURL
      )
    }

    DeployRecord(new DateTime(), uuid, parameters, metaData.getOrElse(Map.empty[String, String]))
  }

  lazy val completed: Observable[UUID] = deployCompleteSubject
  private lazy val deployCompleteSubject = Subject[UUID]()

  private val messagesSubscription: Subscription =
    DeployReporter.messages.subscribe{ wrapper =>
      try {
        update(wrapper)
      } catch {
        case NonFatal(t) =>
          log.error(s"Exception thrown whilst processing update with $wrapper", t)
      }
    }
  def init() {}
  def shutdown() { messagesSubscription.unsubscribe() }


  val deploysEnabled = new Switch(startAsOn = true)

  /**
    * Attempt to disable deploys from running.
 *
    * @return true if successful and false if this failed because a deploy was currently running
    */
  def atomicDisableDeploys:Boolean = {
    try {
      deploysEnabled.switchOff {
        if (getControllerDeploys.exists(!_.isDone))
          throw new IllegalStateException("Cannot turn switch off as builds are currently running")
      }
      true
    } catch {
      case e:IllegalStateException => false
    }
  }

  def enableDeploys = deploysEnabled.switchOn

  lazy val enableSwitches = List(enableDeploysSwitch, enableQueueingSwitch)

  lazy val enableDeploysSwitch = new DefaultSwitch("enable-deploys", "Enable riff-raff to queue and run deploys.  This switch can only be turned off if no deploys are running.", deploysEnabled.isOn) {
    override def switchOff() {
      if (!atomicDisableDeploys) throw new IllegalStateException("Cannot turn switch off as builds are currently running")
      super.switchOff()
    }
    override def switchOn(): Unit = {
      enableDeploys
      super.switchOn()
    }
  }

  lazy val enableQueueingSwitch = new DefaultSwitch("enable-deploy-queuing", "Enable riff-raff to queue deploys.  Turning this off will prevent anyone queueing a new deploy, although running deploys will continue.", true)

  implicit val system = ActorSystem("deploy")

  val library = Agent(Map.empty[UUID,Agent[DeployRecord]])

  def update(wrapper: MessageWrapper) {
    Option(library()(wrapper.context.deployId)) foreach { recordAgent =>
      recordAgent send { record =>
        val updated = record + wrapper
        documentStoreConverter.saveMessage(wrapper)
        if (record.state != updated.state) {
          documentStoreConverter.updateDeployStatus(updated)
        }
        if (record.totalTasks != updated.totalTasks ||
            record.completedTasks != updated.completedTasks ||
            record.hasWarnings != updated.hasWarnings) {
          documentStoreConverter.updateDeploySummary(updated)
        }
        if (updated.isDone) {
          cleanup(record.uuid)
        }
        updated
      }
    }
  }

  def cleanup(uuid: UUID) {
    log.debug(s"Queuing removal of deploy record $uuid from internal caches")
    library sendOff { allDeploys =>
      import cats.syntax.traverse._
      import cats.instances.future._
      import cats.instances.option._

      val record: Option[DeployRecord] =
        Await.result(
          allDeploys.get(uuid).traverse(_.future()),
          10 seconds
        )

      record match {
        case None =>
          log.warn(s"$uuid not found in internal caches")
          allDeploys
        case Some(rec) =>
          log.debug(s"About to  remove deploy record $uuid from internal caches")
          allDeploys - rec.uuid
      }
    }
    firePostCleanup(uuid)
  }

  def firePostCleanup(uuid: UUID) {
    library.future().onComplete{ _ =>
      deployCompleteSubject.onNext(uuid)
    }
  }
  def getControllerDeploys: Iterable[Record] = { library().values.map{ _() } }
  def getDatastoreDeploys(filter:Option[DeployFilter] = None, pagination: PaginationView, fetchLogs: Boolean): Either[Throwable, List[Record]] = {
    documentStoreConverter.getDeployList(filter, pagination, fetchLogs)
  }

  def getDeploys(filter:Option[DeployFilter] = None, pagination: PaginationView = PaginationView(), fetchLogs: Boolean = false): Either[Throwable, List[Record]] = {
    require(!fetchLogs || pagination.pageSize.isDefined, "Too much effort required to fetch complete record with no pagination")
    getDatastoreDeploys(filter, pagination, fetchLogs=fetchLogs).map(_.sortWith{ _.time.getMillis < _.time.getMillis })
  }

  def getLastCompletedDeploys(project: String): Map[String, Record] = {
    documentStoreConverter.getLastCompletedDeploys(project)
  }

  def findProjects: Either[Throwable, List[String]] =
    documentStoreConverter.findProjects

  def countDeploys(filter:Option[DeployFilter]) = documentStoreConverter.countDeploys(filter)

  def markAsFailed(record: Record) {
    documentStoreConverter.updateDeployStatus(record.uuid, RunState.Failed)
  }

  def get(uuid: UUID, fetchLog: Boolean = true): Record = {
    val agent = library().get(uuid)
    agent.map(_()).getOrElse {
      documentStoreConverter.getDeploy(uuid, fetchLog).get
    }
  }

  def await(uuid: UUID): Record = {
    library()(uuid)()
  }
}