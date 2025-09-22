package postgres

import java.util.UUID
import controllers.ApiKey
import magenta.RunState
import magenta.Strategy.MostlyHarmless
import org.joda.time.DateTime
import persistence.{
  AllDocument,
  DeployRecordDocument,
  LogDocument,
  MessageDocument,
  ParametersDocument
}

import scala.util.Random

object TestData {
  def dateTime = DateTime.now().minusMinutes(new Random().nextInt(10))

  def someApiKey: ApiKey = new ApiKey(
    application = "test-application",
    key = UUID.randomUUID().toString,
    issuedBy = "guardian-developer",
    created = dateTime,
    lastUsed = Some(dateTime),
    callCounters = Map("history" -> 10)
  )

  def someDeploy: DeployRecordDocument = {
    val uuid = UUID.randomUUID()
    new DeployRecordDocument(
      uuid = uuid,
      stringUUID = Some(uuid.toString),
      startTime = dateTime,
      parameters = ParametersDocument(
        deployer = s"developer.$uuid@gu.com",
        projectName = s"project-name-$uuid",
        buildId = s"id-$uuid",
        stage = "TEST",
        tags = Map.empty,
        selector = AllDocument,
        updateStrategy = None
      ),
      status = RunState.Completed,
      summarised = Some(true),
      totalTasks = Some(3),
      completedTasks = Some(1),
      lastActivityTime = Some(dateTime),
      hasWarnings = Some(false)
    )
  }

  def someLogDocument(document: MessageDocument): LogDocument = new LogDocument(
    deploy = UUID.randomUUID(),
    id = UUID.randomUUID(),
    parent = Some(UUID.randomUUID()),
    document = document,
    time = dateTime
  )
}
