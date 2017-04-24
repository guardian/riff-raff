package deployment.actors

import java.util.UUID

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorRef, ActorRefFactory, OneForOneStrategy, Terminated}
import akka.agent.Agent
import cats.data.Validated.{Invalid, Valid}
import controllers.Logging
import deployment.Record
import magenta.artifact._
import magenta.deployment_type.DeploymentType
import magenta.graph.{DeploymentGraph, DeploymentTasks, Graph, StartNode, ValueNode}
import magenta.input.resolver.Resolver
import magenta.json.JsonReader
import magenta.{DeployContext, DeployReporter, DeployStoppedException, DeploymentResources, Region}
import org.joda.time.DateTime
import resources.PrismLookup

import scala.util.control.NonFatal

class DeployGroupRunner(
    record: Record,
    deployCoordinator: ActorRef,
    deploymentRunnerFactory: (ActorRefFactory, String) => ActorRef,
    stopFlagAgent: Agent[Map[UUID, String]],
    prismLookup: PrismLookup,
    deploymentTypes: Seq[DeploymentType]
) extends Actor
    with Logging {
  import DeployGroupRunner._

  override def supervisorStrategy() = OneForOneStrategy() {
    case throwable =>
      log.warn("DeploymentRunner died with exception", throwable)
      Stop
  }

  val rootReporter = DeployReporter.startDeployContext(DeployReporter.rootReporterFor(record.uuid, record.parameters))
  var rootContextClosed = false

  var deployContext: Option[DeployContext] = None

  var executing: Set[ValueNode[DeploymentTasks]] = Set.empty
  var completed: Set[ValueNode[DeploymentTasks]] = Set.empty
  var failed: Set[ValueNode[DeploymentTasks]] = Set.empty

  def deploymentGraph: Graph[DeploymentTasks] = deployContext.map(_.tasks).getOrElse(Graph.empty[DeploymentTasks])
  def allDeployments = deploymentGraph.nodes.filterValueNodes

  def isFinished: Boolean = allDeployments == completed ++ failed
  def isExecuting: Boolean = executing.nonEmpty

  def first: List[ValueNode[DeploymentTasks]] = deploymentGraph.orderedSuccessors(StartNode).filterValueNodes
  /* these two functions can return a number of things
      - Deployments: list of deployments
      - FinishPath: indicator there are no more tasks on this path
      - FinishDeploy: indicator that there are no more tasks for this deploy
      first will actually only ever return the first of these.  */
  def next(deployment: DeploymentTasks): NextResult = {
    // if this was a last node and there is no other nodes executing then there is nothing left to do
    if (isFinished) DeployFinished
    // otherwise let's see what children are valid to return
    else {
      // candidates are all successors not already executing or completing
      val nextDeploymentCandidates =
        deploymentGraph.orderedSuccessors(deploymentGraph.get(deployment)).filterValueNodes
      // now filter for only tasks whose predecessors are all completed
      val nextDeployments = nextDeploymentCandidates.filter { deployment =>
        (deploymentGraph.predecessors(deployment) -- completed).isEmpty
      }
      if (nextDeployments.nonEmpty) {
        NextTasks(nextDeployments)
      } else {
        DeployUnfinished
      }
    }
  }
  protected[deployment] def markExecuting(deployment: DeploymentTasks) = {
    executing += deploymentGraph.get(deployment)
  }
  protected[deployment] def markComplete(deployment: DeploymentTasks) = {
    val node = deploymentGraph.get(deployment)
    executing -= node
    completed += node
  }
  protected[deployment] def markFailed(deployment: DeploymentTasks) = {
    val node = deploymentGraph.get(deployment)
    executing -= node
    failed += node
  }
  def finishRootContext() = {
    rootContextClosed = true
    DeployReporter.finishContext(rootReporter)
  }
  def failRootContext() = {
    rootContextClosed = true
    DeployReporter.failContext(rootReporter)
  }
  def failRootContext(message: String, exception: Throwable) = {
    rootContextClosed = true
    DeployReporter.failContext(rootReporter, message, exception)
  }
  private def cleanup() = {
    if (!rootContextClosed) finishRootContext()
    deployCoordinator ! DeployCoordinator.CleanupDeploy(record.uuid)
    context.stop(self)
  }

  override def toString: String = {
    s"""
       |UUID: ${record.uuid.toString}
       |#Tasks: ${allDeployments.size}
       |#Executing: ${executing.mkString("; ")}
       |#Completed: ${completed.size} Failed: ${failed.size}
       |#Done: ${completed.size + failed.size}
     """.stripMargin
  }

  def receive = {
    case Start =>
      try {
        self ! ContextCreated(createContext)
        self ! StartDeployment
      } catch {
        case NonFatal(t) =>
          if (!rootContextClosed) failRootContext("Preparing deploy failed", t)
          cleanup()
      }

    case ContextCreated(preparedContext) =>
      deployContext = Some(preparedContext)

    case StartDeployment =>
      reportTasks()
      runTasks(first)

    case DeploymentCompleted(tasks) =>
      log.debug("Deployment completed")
      markComplete(tasks)
      next(tasks) match {
        case NextTasks(pendingTasks) =>
          runTasks(pendingTasks)
        case DeployUnfinished =>
        case DeployFinished =>
          cleanup()
      }

    case DeploymentFailed(tasks, exception) =>
      log.debug("Deployment failed")
      markFailed(tasks)
      if (isExecuting) {
        log.debug("Failed during deployment but others still running - deferring clean up")
      } else {
        cleanup()
      }

    case Terminated(actor) =>
      if (!rootContextClosed)
        failRootContext("DeploymentRunner unexpectedly terminated",
                        new RuntimeException("DeploymentRunner unexpectedly terminated"))
      log.warn(s"Received terminate from ${actor.path}")
  }

  private def createContext: DeployContext = {
    DeployReporter.withFailureHandling(rootReporter) { implicit safeReporter =>
      import cats.syntax.either._
      import conf.Configuration._

      implicit val client = artifact.aws.client
      val bucketName = artifact.aws.bucketName

      safeReporter.info("Reading riff-raff.yaml")
      val resources = DeploymentResources(safeReporter, prismLookup, client)

      val riffRaffYaml = S3YamlArtifact(record.parameters.build, bucketName)
      val riffRaffYamlString = riffRaffYaml.deployObject.fetchContentAsString()(client)

      val context: Either[ArtifactResolutionError, DeployContext] = riffRaffYamlString.map { yaml =>
        val graph = Resolver.resolve(yaml, resources, record.parameters, deploymentTypes, riffRaffYaml)
        graph.map(DeployContext(record.uuid, record.parameters, _)) match {
          case Invalid(errors) =>
            errors.errors.toList.foreach(error => safeReporter.warning(s"${error.context}: ${error.message}"))
            safeReporter.fail(s"Failed to successfully resolve the deployment: ${errors.errors.toList.size} errors")
          case Valid(success) => success
        }
      } orElse {
        safeReporter.info("Falling back to deploy.json")
        val s3Artifact = S3JsonArtifact(record.parameters.build, bucketName)
        val json = S3JsonArtifact.fetchInputFile(s3Artifact)
        val project = json.map(JsonReader.buildProject(_, s3Artifact, deploymentTypes))
        project.map(
          DeployContext(record.uuid, record.parameters, _, resources, Region(target.aws.deployJsonRegionName)))
      }

      val c = context
        .recover {
          case S3ArtifactError(EmptyS3Location(location)) => safeReporter.fail(s"No file found at $location")
          case S3ArtifactError(UnknownS3Error(e)) => safeReporter.fail("Error while resolving deploy context", e)
          case JsonArtifactError(parseErrors) => safeReporter.fail(s"Couldn't parse `deploy.json`: $parseErrors")
        }
        .getOrElse(safeReporter.fail("Unexpected error while resolving deploy context"))

      if (DeploymentGraph.toTaskList(c.tasks).isEmpty)
        safeReporter.fail(
          "No tasks were found to execute. Ensure the app(s) are in the list supported by this stage/host.")

      c
    }
  }

  private def reportTasks() = {
    deployContext.foreach(c => rootReporter.taskList(c.tasks.toList.flatMap(_.tasks)))
  }

  private var actorIndex = 0
  private def nextActorName() = {
    actorIndex += 1
    s"${record.uuid}-$actorIndex"
  }

  private def runTasks(tasksList: List[ValueNode[DeploymentTasks]]) = {
    try {
      honourStopFlag(rootReporter) {
        tasksList.zipWithIndex.foreach {
          case (ValueNode(tasks), index) =>
            val actorName = nextActorName()
            log.debug(s"Running next set of tasks (${tasks.name}/$index) on actor $actorName")
            val deploymentRunner = context.watch(deploymentRunnerFactory(context, actorName))
            deploymentRunner ! TasksRunner.RunDeployment(record.uuid, tasks, rootReporter, new DateTime())
            markExecuting(tasks)
        }
      }
    } catch {
      case NonFatal(t) => log.error("Couldn't run tasks", t)
    }
  }

  private def honourStopFlag(reporter: DeployReporter)(elseBlock: => Unit) {
    stopFlagAgent().get(record.uuid) match {
      case Some(userName) =>
        log.debug("Stop flag set")
        val stopMessage = s"Deploy has been stopped by $userName"
        if (!isExecuting) {
          DeployReporter.failContext(rootReporter, stopMessage, DeployStoppedException(stopMessage))
          log.debug("Cleaning up")
          cleanup()
        }

      case None =>
        elseBlock
    }
  }

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    log.debug(s"Deployment group runner ${self.path} stopped")
    if (!rootContextClosed) failRootContext()
    super.postStop()
  }
}

object DeployGroupRunner {
  sealed trait NextResult
  case class NextTasks(tasksList: List[ValueNode[DeploymentTasks]]) extends NextResult
  case object DeployUnfinished extends NextResult
  case object DeployFinished extends NextResult

  sealed trait Message
  case object Start extends Message
  case class ContextCreated(context: DeployContext) extends Message
  case object StartDeployment extends Message
  case class DeploymentCompleted(tasks: DeploymentTasks) extends Message
  case class DeploymentFailed(tasks: DeploymentTasks, exception: Throwable) extends Message
}
