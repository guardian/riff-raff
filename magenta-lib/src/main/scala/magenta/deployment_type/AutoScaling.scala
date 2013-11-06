package magenta.deployment_type

import magenta.tasks._
import java.io.File
import magenta.{DeployParameters, DeployInfo, Package}

object AutoScaling  extends DeploymentType {
  val name = "autoscaling"
  lazy val documentation =
    ("""Deploy to an autoscaling group in AWS.
        |
        |#### Possible actions:
      """.stripMargin :: actionDocumentation.toList) mkString "\n\n"

  lazy val actionDocumentation = for {
    (name, actions) <- applicationActions
  } yield {
    val actionsDescription = actions map (a => s": ${a.description}") mkString "\n"
    name + "\n" +actionsDescription
  }

  val bucket = Param[String]("bucket",
    """
      |S3 bucket name to upload artifact into.
      |
      |The path in the bucket is `<stage>/<packageName>/<fileName>`.
    """.stripMargin
  )
  val secondsToWait = Param("secondsToWait", "Number of seconds to wait for instances to enter service").default(15 * 60)
  val healthcheckGrace = Param("healthcheckGrace", "Number of seconds to wait for the AWS api to stabalise").default(0)

  val applicationActions = Map(
    "deploy" -> List(
      CheckGroupSize,
      SuspendAlarmNotifications,
      TagCurrentInstancesWithTerminationTag,
      DoubleSize,
      WaitForStabilization(secondsToWait),
      HealthcheckGrace(healthcheckGrace),
      WaitForStabilization(secondsToWait),
      CullInstancesWithTerminationTag,
      ResumeAlarmNotifications
    ),
    "uploadArtifacts" -> List(
      S3Upload(bucket)
    )
  )

  def perAppActions = {
    val pfs = for {
      (name, actions) <- applicationActions
    } yield new PartialFunction[String, Package => (DeployInfo, DeployParameters) => List[Task]] {
      def apply(name: String) = (pkg) => (_, parameters) => actions map (_(pkg, parameters))
      def isDefinedAt(actionName: String) = actionName == name
    }
    pfs.toSeq.reduce(_ orElse _)
  }
}
