package magenta.tasks

import java.io.{ByteArrayInputStream, IOException}

import cats.data.NonEmptyList
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.deploymentmanager.{DeploymentManager, DeploymentManagerScopes}
import com.google.api.services.deploymentmanager.model.{ConfigFile, Deployment, ImportFile, Operation, TargetConfiguration}
import com.gu.management.Loggable
import magenta.KeyRing

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.pattern.after
import com.google.api.services.deploymentmanager.model.Operation.Error.Errors

object GCP {
  lazy val httpTransport: NetHttpTransport = GoogleNetHttpTransport.newTrustedTransport
  lazy val jsonFactory: JacksonFactory = JacksonFactory.getDefaultInstance
  val scopes: Seq[String] = Seq(
    DeploymentManagerScopes.CLOUD_PLATFORM
  )

  object credentials {
    def getCredentials(keyring: KeyRing): Option[GoogleCredential] = {
      keyring.apiCredentials.get("gcp").map { credentials =>
        val in = new ByteArrayInputStream(credentials.secret.getBytes)
        GoogleCredential
          .fromStream(in)
          .createScoped(scopes.asJava)
      }
    }
  }

  class GCPDeploymentManager(val actorSystem: ActorSystem) {

    sealed trait DMOperation {
      val project: String
      val id: String
    }
    case class InProgress(id: String, project: String) extends DMOperation
    case class Success(id: String, project: String) extends DMOperation
    case class Failure(id: String, project: String, error: List[Errors]) extends DMOperation

    object DMOperation {
      def apply(operation: Operation, project: String): DMOperation = {
        val id = operation.getClientOperationId
        (Option(operation.getError.getErrors), operation.getStatus) match {
          case (_, "PENDING") => InProgress(id, project)
          case (_, "RUNNING") => InProgress(id, project)
          case (Some(errors), "DONE") => Failure(id, project, errors.asScala.toList)
          case (None, "DONE") => Success(id, project)
        }
      }
    }

    case class DeploymentBundle(config: String, deps: Map[String, String])

    def client(creds: GoogleCredential) = {
      new DeploymentManager.Builder(httpTransport, jsonFactory, creds).build()
    }

    def toTargetConfiguration(bundle: DeploymentBundle): TargetConfiguration = {
      val configFile = new ConfigFile().setContent(bundle.config)
      val imports = bundle.deps.map { case (name, data) =>
        new ImportFile().setName(name).setContent(data)
      }
      new TargetConfiguration().setConfig(configFile).setImports(imports.toList.asJava)
    }

    def list(client: DeploymentManager, project: String): List[Deployment] = {
      val response = client.deployments.list(project).execute()
      // TODO - this should do pagination of projects, for now fail fast
      if (Option(response.getNextPageToken).nonEmpty) throw new IllegalStateException("We don't support paginated deployments and we've just got a response that has a second page")
      response.getDeployments.asScala.toList
    }

    def insert(client: DeploymentManager, project: String, name: String, bundle: DeploymentBundle)(implicit actorSystem: ActorSystem): Future[DMOperation] = {
      implicit val ec: ExecutionContext = actorSystem.dispatcher
      val target = toTargetConfiguration(bundle)

      val content = new Deployment().setName(name).setTarget(target)
      api.retryWhen500orGoogleError(
        () => client.deployments().insert(project, content).execute(),
        s"Deploy Manager insert $project/$name"
      ).map (DMOperation(_, project))
    }

    def update(client: DeploymentManager, project: String, name: String, bundle: DeploymentBundle)(implicit actorSystem: ActorSystem): Future[DMOperation] = {
      implicit val ec: ExecutionContext = actorSystem.dispatcher
      val target = toTargetConfiguration(bundle)

      val content = new Deployment().setTarget(target)
      api.retryWhen500orGoogleError(
        () => client.deployments().update(project, name, content).execute(),
        s"Deploy Manager update $project/$name"
      ).map (DMOperation(_, project))
    }

//    def upsert(client: DeploymentManager, project: String, name: String, bundle: DeploymentBundle)(implicit executionContext: ExecutionContext): Future[Operation] = {
//      if (!list(client, project).exists(_.getName == name)) {
//        insert(client, project, name, bundle)
//      } else {
//        update(client, project, name, bundle)
//      }
//    }

    def pollOperation(client: DeploymentManager, operation: DMOperation)(implicit actorSystem: ActorSystem) = {
      implicit val ec: ExecutionContext = actorSystem.dispatcher
      api.retryWhen500orGoogleError(
        () => client.operations.get(operation.project, operation.id).execute(),
        s"Deploy Manager operation get ${operation.project}/${operation.id}"
      ).map { DMOperation(_, operation.project)
      }
    }
  }

  object api {
    def retryWhen500orGoogleError[T](op: () => T, failureMessage: String)(implicit actorSystem: ActorSystem): Future[T] = {
      implicit val ec: ExecutionContext = actorSystem.dispatcher
      Retry.retryableFutureToFuture(
        Retry.retryExponentially(
          when500orGoogleError,
          failureMessage
        )(
          () => Future(blocking(op()))
        )
      )
    }

    protected def when500orGoogleError(throwable: Throwable): Boolean = {
      throwable match {
        case t: GoogleJsonResponseException => {
          ((t.getStatusCode == 403 || t.getStatusCode == 429) && t.getDetails.getErrors.asScala.head.getDomain.equalsIgnoreCase("usageLimits")) ||
            (t.getStatusCode == 400 && t.getDetails.getErrors.asScala.head.getReason.equalsIgnoreCase("invalid")) ||
            t.getStatusCode == 404 ||
            t.getStatusCode/100 == 5
        }
        case t: HttpResponseException => t.getStatusCode/100 == 5
        case ioe: IOException => true
        case _ => false
      }
    }
  }
}

/**
  * Borrowed from https://github.com/broadinstitute
  */
object Retry extends Loggable {
  type Predicate[A] = A => Boolean

  /**
    * A Future that has potentially been retried, with accumulated errors.
    * There are 3 cases:
    * 1. The future failed 1 or more times, and the final result is an error.
    *   - This is represented as {{{Left(NonEmptyList(errors))}}}
    * 2. The future failed 1 or more times, but eventually succeeded.
    *   - This is represented as {{{Right(List(errors), A)}}}
    * 3. The future succeeded the first time.
    *   - This is represented as {{{Right(List.empty, A)}}}
    */
  type RetryableFuture[A] = Future[Either[NonEmptyList[Throwable], (List[Throwable], A)]]

  def always[A]: Predicate[A] = _ => true

  def retryExponentially[T](pred: Predicate[Throwable] = always, failureLogMessage: String)(op: () => Future[T])(implicit actorSystem: ActorSystem): RetryableFuture[T] = {
    retryInternal(exponentialBackOffIntervals, pred, failureLogMessage)(op)
  }

  /**
    * will retry at the given interval until success or the overall timeout has passed
    * @param pred which failures to retry
    * @param interval how often to retry
    * @param timeout how long from now to give up
    * @param op what to try
    * @tparam T
    * @return
    */
  def retryUntilSuccessOrTimeout[T](pred: Predicate[Throwable] = always, failureLogMessage: String)(interval: FiniteDuration, timeout: FiniteDuration)(op: () => Future[T])(implicit actorSystem: ActorSystem): RetryableFuture[T] = {
    val trialCount = Math.ceil(timeout / interval).toInt
    retryInternal(Seq.fill(trialCount)(interval), pred, failureLogMessage)(op)
  }

  private def retryInternal[T](backoffIntervals: Seq[FiniteDuration],
    pred: Predicate[Throwable],
    failureLogMessage: String)
    (op: () => Future[T])
    (implicit actorSystem: ActorSystem): RetryableFuture[T] = {
    implicit val ec: ExecutionContext = actorSystem.dispatcher

    def loop(remainingBackoffIntervals: Seq[FiniteDuration], errors: => List[Throwable]): RetryableFuture[T] = {
      op().map(x => Right((errors, x))).recoverWith {
        case t if pred(t) && remainingBackoffIntervals.nonEmpty =>
          logger.info(s"$failureLogMessage: ${remainingBackoffIntervals.size} retries remaining, retrying in ${remainingBackoffIntervals.head}", t)
          after(remainingBackoffIntervals.head, actorSystem.scheduler) {
            loop(remainingBackoffIntervals.tail, t :: errors)
          }

        case t =>
          if (remainingBackoffIntervals.isEmpty) {
            logger.info(s"$failureLogMessage: no retries remaining", t)
          } else {
            logger.info(s"$failureLogMessage: retries remain but predicate failed, not retrying", t)
          }

          Future.successful(Left(NonEmptyList(t, errors)))
      }
    }

    loop(backoffIntervals, List.empty)
  }

  protected def exponentialBackOffIntervals: Seq[FiniteDuration] = {
    val plainIntervals = Seq(1000 milliseconds, 2000 milliseconds, 4000 milliseconds, 8000 milliseconds, 16000 milliseconds, 32000 milliseconds)
    plainIntervals.map(i => addJitter(i, 1000 milliseconds))
  }

  def addJitter(baseTime: FiniteDuration, maxJitterToAdd: Duration): FiniteDuration = {
    baseTime + ((scala.util.Random.nextFloat * maxJitterToAdd.toNanos) nanoseconds)
  }

  /**
    * Converts an RetryableFuture[A] to a Future[A].
    */
  implicit def retryableFutureToFuture[A](af: RetryableFuture[A])(implicit executionContext: ExecutionContext): Future[A] = {
    af.flatMap {
      // take the head (most recent) error
      case Left(NonEmptyList(t, _)) => Future.failed(t)
      // return the successful result, throw out any errors
      case Right((_, a)) => Future.successful(a)
    }
  }

}
