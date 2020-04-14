package magenta.tasks.gcp

import magenta.{DeployReporter, KeyRing}
import magenta.tasks.{PollingCheck, Task}
import magenta.tasks.gcp.Gcp.DeploymentManagerApi._

import scala.concurrent.duration.FiniteDuration

object DeploymentManagerTasks {
  def updateTask(project: String, deploymentName: String, bundle: DeploymentBundle, maxWait: FiniteDuration, upsert: Boolean)(implicit kr: KeyRing): Task = new Task with PollingCheck {

    override def name: String = "DeploymentManagerUpdate"

    override def keyRing: KeyRing = kr
    override def description: String = "Update a deployment manager deployment in GCP"
    override def verbose: String = s"Update deployment '$deploymentName' in GCP project '$project' using ${bundle.configPath} and $dependencyDesc"
    def dependencyDesc: String = {
      bundle.deps.keys.toList match {
        case Nil => "no dependencies"
        case singleton :: Nil => s"dependency $singleton"
        case multiple => s"dependencies ${multiple.mkString(", ")}"
      }
    }

    val untilFinished: DMOperation => Boolean = {
      case InProgress(_, _) => false
      case _ => true
    }

    override def execute(reporter: DeployReporter, stopFlag: => Boolean): Unit = {
      val credentials = Gcp.credentials.getCredentials(keyRing).getOrElse(reporter.fail("Unable to build GCP credentials from keyring"))
      val client = Gcp.DeploymentManagerApi.client(credentials)

      val maybeDeployment = Gcp.DeploymentManagerApi.get(client, project, deploymentName)

      val maybeOperation = maybeDeployment match {
        case Some(deployment) =>
          reporter.verbose(s"Fetched details for deployment ${deploymentName}; using fingerprint ${deployment.getFingerprint} for update")
          Gcp.DeploymentManagerApi.update(client, project, deploymentName, deployment.getFingerprint, bundle)(reporter)
        case None =>
          if (upsert) {
            reporter.verbose(s"Deployment ${deploymentName} doesn't exist; inserting new deployment")
            Gcp.DeploymentManagerApi.insert(client, project, deploymentName, bundle)(reporter)
          } else {
            reporter.fail(s"Deployment ${deploymentName} doesn't exist and upserting isn't enabled.")
          }
      }

      maybeOperation.fold(
        error => reporter.fail("DeployManager update operation failed", error),
        operation => {
          check(reporter, stopFlag) {
            val maybeUpdatedOperation = Gcp.DeploymentManagerApi.operationStatus(client, operation)(reporter)
            maybeUpdatedOperation.fold(
              error => reporter.fail("DeployManager operation status failed", error),
              {
                case Success(_, _) => true // exit check
                case Failure(_, _, errors) =>
                  errors.foreach { error =>
                    reporter.verbose(s"Operation error: $error")
                  }
                  reporter.fail("Failed to successfully execute update, errors logged verbosely")
                case _ => false // continue check
              }
            )
          }
        }
      )
    }

    override def duration: Long = maxWait.toMillis

    override def calculateSleepTime(currentAttempt: Int): Long = 5000
  }
}
