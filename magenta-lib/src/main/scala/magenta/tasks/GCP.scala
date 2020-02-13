package magenta.tasks

import java.io.ByteArrayInputStream

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.deploymentmanager.{DeploymentManager, DeploymentManagerScopes}
import com.google.api.services.deploymentmanager.model.{ConfigFile, Deployment, ImportFile, Operation, TargetConfiguration}
import magenta.KeyRing

import scala.collection.JavaConverters._

object GCP {
  lazy val httpTransport: NetHttpTransport = GoogleNetHttpTransport.newTrustedTransport
  lazy val jsonFactory: JacksonFactory = JacksonFactory.getDefaultInstance
  val saScopes = Seq(
    "https://www.googleapis.com/auth/cloud-billing",
    DeploymentManagerScopes.CLOUD_PLATFORM
  )

  object credentials {
    def getCredentials(keyring: KeyRing): Option[GoogleCredential] = {
      keyring.apiCredentials.get("gcp").map { credentials =>
        val in = new ByteArrayInputStream(credentials.secret.getBytes)
        GoogleCredential
          .fromStream(in)
          .createScoped(saScopes.asJava)
      }
    }
  }

  object deploymentManager {

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
      // TODO - this should do pagination of projects
      client.deployments.list(project).execute().getDeployments.asScala.toList
    }

    def insert(client: DeploymentManager, project: String, name: String, bundle: DeploymentBundle): Operation = {
      val target = toTargetConfiguration(bundle)

      val content = new Deployment().setName(name).setTarget(target)
      client.deployments().insert(project, content).execute()
    }

    def update(client: DeploymentManager, project: String, name: String, bundle: DeploymentBundle): Operation = {
      val target = toTargetConfiguration(bundle)

      val content = new Deployment().setTarget(target)
      client.deployments().update(project, name, content).execute()
    }

    def upsert(client: DeploymentManager, project: String, name: String, bundle: DeploymentBundle): Operation = {
      if (!list(client, project).exists(_.getName == name)) {
        insert(client, project, name, bundle)
      } else {
        update(client, project, name, bundle)
      }
    }

    def pollOperation(client: DeploymentManager, project: String, operation: Operation) = {
      client.operations.get(project, operation.getClientOperationId)
    }
  }
}
