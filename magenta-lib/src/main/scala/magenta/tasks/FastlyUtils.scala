package magenta.tasks
import com.gu.fastly.api.FastlyApiClient
import magenta.{DeployParameters, DeployReporter, DeployStoppedException}
import play.api.libs.json.Json

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

trait FastlyUtils {
  protected def block[T](f: => Future[T]): T = Await.result(f, 1.minute)

  protected def stopOnFlag[T](stopFlag: => Boolean)(block: => T): T =
    if (!stopFlag) block
    else
      throw DeployStoppedException(
        "Deploy manually stopped during UpdateFastlyConfig"
      )

  protected def getActiveVersionNumber(
      client: FastlyApiClient,
      reporter: DeployReporter,
      stopFlag: => Boolean
  ): Int = {
    stopOnFlag(stopFlag) {
      val versionList = block(client.versionList())
      val versions = Json.parse(versionList.getResponseBody).as[List[Version]]
      val activeVersion = versions.filter(x => x.active.getOrElse(false)).head
      reporter.info(s"Current active version ${activeVersion.number}")
      activeVersion.number
    }
  }

  protected def clone(
      versionNumber: Int,
      client: FastlyApiClient,
      reporter: DeployReporter,
      stopFlag: => Boolean
  ): Int = {
    stopOnFlag(stopFlag) {
      val cloned = block(client.versionClone(versionNumber))
      val clonedVersion = Json.parse(cloned.getResponseBody).as[Version]
      reporter.info(s"Cloned version ${clonedVersion.number}")
      clonedVersion.number
    }
  }

  protected def commentVersion(
      versionNumber: Int,
      client: FastlyApiClient,
      reporter: DeployReporter,
      parameters: DeployParameters,
      stopFlag: => Boolean
  ): Unit = {
    stopOnFlag(stopFlag) {
      reporter.info("Adding deployment information")
      block(
        client.versionComment(
          versionNumber,
          s"""
          Deployed via Riff-Raff by ${parameters.deployer.name}
          (${parameters.build.projectName}, ${parameters.stage.name},
          build ${parameters.build.id})
          """
        )
      )
    }
  }
}
