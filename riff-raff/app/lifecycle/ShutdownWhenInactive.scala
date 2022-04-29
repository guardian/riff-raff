package lifecycle

import com.gu.anghammarad.Anghammarad
import com.gu.anghammarad.models._
import conf.Config
import controllers.Logging
import deployment.{Deployments, Record}
import magenta.DefaultSwitch
import software.amazon.awssdk.regions.internal.util.EC2MetadataUtils
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._

trait WhenInactive extends Lifecycle with Logging {
  val name: String
  val description: String
  val deployments: Deployments

  def action(): Unit

  // switch to enable this mode
  lazy val switch: DefaultSwitch = new DefaultSwitch(name, description, false) {
    override def switchOn(): Unit = {
      super.switchOn()
      attemptAction()
    }
  }

  def attemptAction(): Unit = {
    Future {
      log.info(s"Attempting to $name: trying to atomically disable deployment")
      if (deployments.atomicDisableDeploys) {
        log.info("Deployment disabled, shutting down JVM")
        // wait a while for AJAX update requests to complete
        blocking(Thread.sleep(2000L))
        action()
      } else {
        val activeBuilds: Iterable[Record] = deployments.getControllerDeploys.filterNot(_.isDone)
        val activeBuildsLog = activeBuilds.map(record => s"${record.uuid} with status ${record.state}")
        log.info(s"RiffRaff not yet inactive (Still running: ${activeBuildsLog.mkString(", ")}), deferring $name request")
      }
    }
  }

  val sub = deployments.completed.subscribe(_ => if (switch.isSwitchedOn) attemptAction())

  // add hooks to listen and exit when desired
  def init(): Unit = { }
  def shutdown(): Unit = { sub.unsubscribe() }
}

class ShutdownWhenInactive(val deployments: Deployments) extends WhenInactive {
  val EXITCODE = 217

  override val name: String = "shutdown-when-inactive"
  override val description: String = s"Shutdown riff-raff when there are no running deploys. Turning this on will cause RiffRaff to exit with exitcode $EXITCODE as soon as the last queued deploy finishes."

  override def action(): Unit = System.exit(EXITCODE)
}

class RotateInstanceWhenInactive(val deployments: Deployments, config: Config) extends WhenInactive {
  override val name: String = "rotate-instance-when-inactive"
  override val description: String = "Rotate underlying EC2 instance to get a new AMI by terminating the instance and expecting the ASG to launch a replacement."

  override def action(): Unit = {
    val instanceId: String = EC2MetadataUtils.getInstanceId

    Anghammarad.notify(
      subject = s"Riff-Raff (${config.stage}) is about to undergo scheduled maintenance",
      message =
        s"""
          |Riff-Raff is about to undergo scheduled maintenance to rotate the AMI.
          |Please anticipate 503 responses whilst this completes.
          |It usually takes around 5 minutes to complete and is starting now as there are not running deploys.
          |
          |The current instance $instanceId will be terminated and replaced by the ASG.
          |
          |""".stripMargin,
      sourceSystem = "riff-raff",
      target = List(Stack("deploy"), App("riff-raff"), Stage(config.stage)),
      actions = List.empty,
      channel = All,
      topicArn = config.management.aws.anghammaradTopicARN,
      client = config.management.aws.snsClient
    ).recover { case ex => log.error(s"Failed to send notification (via Anghammarad)", ex) }

    val request = TerminateInstancesRequest.builder().instanceIds(instanceId).build()
    config.management.aws.ec2Client.terminateInstances(request)
  }
}
