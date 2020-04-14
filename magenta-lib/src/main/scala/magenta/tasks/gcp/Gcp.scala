package magenta.tasks.gcp

import java.io.{ByteArrayInputStream, IOException}

import cats.syntax.either._
import com.google.api.client.googleapis.apache.GoogleApacheHttpTransport
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.apache.ApacheHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.deploymentmanager.model.Operation.Error.Errors
import com.google.api.services.deploymentmanager.model._
import com.google.api.services.deploymentmanager.{DeploymentManager, DeploymentManagerScopes}
import magenta.tasks.gcp.GcpRetryHelper.Result
import magenta.{DeployReporter, KeyRing}

import scala.collection.JavaConverters._

object Gcp {
  lazy val httpTransport: ApacheHttpTransport = GoogleApacheHttpTransport.newTrustedTransport
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

  object DeploymentManagerApi {

    sealed trait DMOperation {
      val project: String
      val id: String
    }
    case class InProgress(id: String, project: String) extends DMOperation
    case class Success(id: String, project: String) extends DMOperation
    case class Failure(id: String, project: String, error: List[Errors]) extends DMOperation

    object DMOperation {
      def apply(operation: Operation, project: String): DMOperation = {
        val operationName = operation.getName
        (Option(operation.getError).flatMap(e => Option(e.getErrors)), operation.getStatus) match {
          case (_, "PENDING") => InProgress(operationName, project)
          case (_, "RUNNING") => InProgress(operationName, project)
          case (Some(errors), "DONE") => Failure(operationName, project, errors.asScala.toList)
          case (None, "DONE") => Success(operationName, project)
          case (_, status) => throw new IllegalArgumentException(s"Unexpected deployment manager operation status $status")
        }
      }
    }

    case class DeploymentBundle(configPath: String, config: String, deps: Map[String, String])

    def client(creds: GoogleCredential): DeploymentManager = {
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

    def get(client: DeploymentManager, project: String, name: String): Deployment = {
      client.deployments.get(project, name).execute()
    }

    def insert(client: DeploymentManager, project: String, name: String, bundle: DeploymentBundle)(reporter: DeployReporter): Result[DMOperation] = {
      val target = toTargetConfiguration(bundle)

      val content = new Deployment().setName(name).setTarget(target)
      api.retryWhen500orGoogleError(
        reporter,
        s"Deploy Manager insert $project/$name"
      ){
        client.deployments().insert(project, content).execute()
      }.map (DMOperation(_, project))
    }

    def update(client: DeploymentManager, project: String, name: String, fingerprint: String, bundle: DeploymentBundle)(reporter: DeployReporter): Result[DMOperation] = {
      val target = toTargetConfiguration(bundle)

      val content = new Deployment().setName(name).setFingerprint(fingerprint).setTarget(target)
      api.retryWhen500orGoogleError(
        reporter,
        s"Deploy Manager update $project/$name"
      ){
        client.deployments().update(project, name, content)
          .setCreatePolicy("CREATE_OR_ACQUIRE")
          .setDeletePolicy("DELETE")
          .setPreview(false)
          .execute()
      }.map (DMOperation(_, project))
    }

//    def upsert(client: DeploymentManager, project: String, name: String, bundle: DeploymentBundle)(implicit executionContext: ExecutionContext): Future[Operation] = {
//      if (!list(client, project).exists(_.getName == name)) {
//        insert(client, project, name, bundle)
//      } else {
//        update(client, project, name, bundle)
//      }
//    }

    /* Given a DMOperation we can poll until an updated operation reaches a given state */
    def operationStatus(client: DeploymentManager, operation: DMOperation)(reporter: DeployReporter): Result[DMOperation] = {
      api.retryWhen500orGoogleError(
        reporter,
        s"Deploy Manager operation get ${operation.project}/${operation.id}",
      ){
        client.operations.get(operation.project, operation.id).execute()
      }.map(DMOperation(_, operation.project))
    }
  }

  object api {
    def retryWhen500orGoogleError[T](reporter: DeployReporter, failureMessage: String)(op: => T): Result[T] = {
      GcpRetryHelper.retryableToResult(
        GcpRetryHelper.retryExponentially(
          reporter,
          when500orGoogleError,
          failureMessage
        ) {
          Either.catchNonFatal {
            op
          }
        }
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

