package postgres

import java.util.UUID

import controllers.{ApiKey, AuthorisationRecord}
import magenta.RunState
import org.joda.time.DateTime
import persistence.{DeployRecordDocument, DeploymentSelectorDocument, ParametersDocument}

object Generators {
  val dateTime = DateTime.now()

  def someApiKey: ApiKey = new ApiKey(
    application = "test-application",
    key = "test-key",
    issuedBy = "guardian-developer",
    created = dateTime,
    lastUsed = Some(dateTime),
    callCounters = Map("history" -> 10)
  )

  def someAuth: AuthorisationRecord = new AuthorisationRecord(
    email = "developer@gu.com",
    approvedBy = "developer",
    approvedDate = dateTime
  )

  def someDeploy: DeployRecordDocument = {
    val uuid = UUID.randomUUID()
    new DeployRecordDocument(
      uuid = uuid,
      stringUUID = Some(uuid.toString),
      startTime = dateTime,
      parameters = ParametersDocument(
        deployer = "developer@gu.com",
        projectName = "project-name",
        buildId = "some-id",
        stage = "TEST",
        tags = Map.empty,
        selector = DeploymentSelectorDocument.AllDocument
      ),
      status = RunState.Running,
      summarised = Some(true),
      totalTasks = Some(3),
      completedTasks = Some(1),
      lastActivityTime = Some(dateTime),
      hasWarnings = Some(false)
    )
  }
}