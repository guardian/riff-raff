package magenta.tasks.gcp

import java.io.{ByteArrayInputStream, IOException}

import akka.actor.ActorSystem
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.deploymentmanager.model.Operation.Error.Errors
import com.google.api.services.deploymentmanager.model._
import com.google.api.services.deploymentmanager.{DeploymentManager, DeploymentManagerScopes}
import magenta.KeyRing

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, blocking}

object GcpApi {
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
      GcpRetryHelper.retryableFutureToFuture(
        GcpRetryHelper.retryExponentially(
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

