package magenta.tasks.gcp

import magenta.{DeployReporter, KeyRing}
import magenta.tasks.{SlowRepeatedPollingCheck, Task}
import magenta.tasks.gcp.Gcp.DeploymentManagerApi._

import scala.concurrent.duration.FiniteDuration

object DeploymentManagerTasks {
  def updateTask(project: String, name: String, bundle: DeploymentBundle, maxWait: FiniteDuration)(implicit kr: KeyRing): Task = new Task with SlowRepeatedPollingCheck {
    override def keyRing: KeyRing = kr
    override def description: String = "Update a deployment manager deployment in GCP"
    override def verbose: String = s"Update deployment $name in GCP project $project using ${bundle.configPath} and dependencies ${bundle.deps.keys.mkString(", ")}"

    val untilFinished: DMOperation => Boolean = {
      case InProgress(_, _) => false
      case _ => true
    }

    override def execute(reporter: DeployReporter, stopFlag: => Boolean): Unit = {
      val credentials = Gcp.credentials.getCredentials(keyRing).getOrElse(reporter.fail("Unable to build GCP credentials from keyring"))
      val client = Gcp.DeploymentManagerApi.client(credentials)

      val maybeOperation = Gcp.DeploymentManagerApi.update(client, project, name, bundle)(reporter)
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
  }
}
