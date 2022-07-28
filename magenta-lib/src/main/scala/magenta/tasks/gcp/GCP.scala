package magenta.tasks.gcp

import cats.syntax.either._
import com.google.api.client.googleapis.apache.v2.GoogleApacheHttpTransport
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.apache.v2.ApacheHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.deploymentmanager.model.Operation.Error.Errors
import com.google.api.services.deploymentmanager.model._
import com.google.api.services.deploymentmanager.{DeploymentManager, DeploymentManagerScopes}
import com.google.api.services.storage.Storage
import magenta.tasks.gcp.GCPRetryHelper.Result
import magenta.{ApiStaticCredentials, DeployReporter, DeploymentResources, KeyRing, Loggable}

import java.io.{ByteArrayInputStream, IOException}
import scala.jdk.CollectionConverters._

object GCP {
  lazy val httpTransport: ApacheHttpTransport = GoogleApacheHttpTransport.newTrustedTransport
  lazy val jsonFactory: GsonFactory = GsonFactory.getDefaultInstance
  val scopes: Seq[String] = Seq(
    DeploymentManagerScopes.CLOUD_PLATFORM
  )

  object credentials {
    def getCredentials(keyring: KeyRing): Option[GoogleCredential] = {
      keyring.apiCredentials.get("gcp").collect { case credentials: ApiStaticCredentials =>
        val in = new ByteArrayInputStream(credentials.secret.getBytes)
        GoogleCredential
          .fromStream(in)
          .createScoped(scopes.asJava)
      }
    }
  }

  object DeploymentManagerApi extends Loggable {

    sealed trait DMOperation {
      val project: String
      val id: String
    }
    case class InProgress(id: String, project: String, progress: Integer) extends DMOperation
    case class Success(id: String, project: String) extends DMOperation
    case class Failure(id: String, project: String, error: List[Errors]) extends DMOperation

    object DMOperation {
      def apply(operation: Operation, project: String): DMOperation = {
        val operationName = operation.getName
        (Option(operation.getError).flatMap(e => Option(e.getErrors)), operation.getStatus) match {
          case (_, "PENDING") => InProgress(operationName, project, operation.getProgress)
          case (_, "RUNNING") => InProgress(operationName, project, operation.getProgress)
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

    def get(client: DeploymentManager, project: String, name: String)(reporter: DeployReporter): Result[Option[Deployment]] = {
      api.retryWhen500orGoogleError(reporter, "Deployment Manager get"){
        Some(client.deployments.get(project, name).execute())
      } recover {
        case gjre: GoogleJsonResponseException if gjre.getDetails.getCode == 404 => None
      }
    }

    def insert(client: DeploymentManager, project: String, name: String, bundle: DeploymentBundle, preview: Boolean)(reporter: DeployReporter): Result[DMOperation] = {
      val target = toTargetConfiguration(bundle)

      val content = new Deployment().setName(name).setTarget(target)
      api.retryWhen500orGoogleError(
        reporter,
        s"Deploy Manager insert $project/$name"
      ){
        client.deployments().insert(project, content)
          .setCreatePolicy("CREATE_OR_ACQUIRE")
          .setPreview(preview)
          .execute()
      }.map (DMOperation(_, project))
    }

    def update(client: DeploymentManager, project: String, name: String, fingerprint: String, bundle: DeploymentBundle, preview: Boolean)(reporter: DeployReporter): Result[DMOperation] = {
      val target = toTargetConfiguration(bundle)

      val content = new Deployment().setName(name).setFingerprint(fingerprint).setTarget(target)
      api.retryWhen500orGoogleError(
        reporter,
        s"Deploy Manager update $project/$name"
      ){
        client.deployments().update(project, name, content)
          .setCreatePolicy("CREATE_OR_ACQUIRE")
          .setDeletePolicy("DELETE")
          .setPreview(preview)
          .execute()
      }.map (DMOperation(_, project))
    }

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

  object StorageApi extends Loggable {
    def client(credentials: GoogleCredential): Storage = {
      new Storage.Builder(httpTransport, jsonFactory, credentials).build()
    }
  }

  object api {
    def retryWhen500orGoogleError[T](reporter: DeployReporter, failureMessage: String)(op: => T): Result[T] = {
      GCPRetryHelper.retryableToResult(
        GCPRetryHelper.retryExponentially(
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
            t.getStatusCode/100 == 5
        }
        case t: HttpResponseException => t.getStatusCode/100 == 5
        case ioe: IOException => true
        case _ => false
      }
    }
  }
}

object GCS {
  def withGCSClient[T](keyRing: KeyRing, resources: DeploymentResources)(block: Storage => T): T = block(GCP.StorageApi.client(
    credentials = GCP.credentials.getCredentials(keyRing).getOrElse(resources.reporter.fail("Unable to build GCP credentials from keyring"))
  ))
}

case class GCSPath(bucket: String, key: String) {
  def show(): String = s"Bucket: '$bucket', Key: '$key'"
}
