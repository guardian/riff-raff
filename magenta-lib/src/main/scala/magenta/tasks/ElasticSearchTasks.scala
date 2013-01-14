package magenta.tasks

import magenta.{KeyRing, Stage}
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import collection.JavaConversions._
import dispatch._
import net.liftweb.json._

case class WaitForElasticSearchClusterGreen(packageName: String, stage: Stage, duration: Long)
  extends ASGTask with RepeatedPollingCheck {
  implicit val format = DefaultFormats

  val description = "Wait for the elasticsearch cluster status to be green"
  override val verbose =
    """Minimise thrashing while rebalancing by waiting until the elasticsearch cluster status is green.
      |Requires access to port 9200 on cluster members.
    """.stripMargin

  def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing) {
    val instance = EC2(asg.getInstances().headOption.getOrElse {
      throw new IllegalArgumentException("Auto-scaling group: %s had no instances" format (asg))
    })
    check {
      val http = new Http()
      http(:/(instance.getPublicDnsName, 9200) / "_cluster" / "health" >- {json =>
        val health = parse(json)
        (health \ "number_of_data_nodes").extract[Int] == asg.getDesiredCapacity &&
        (health \ "status").extract[String] == "green"
      })
    }
  }
}

case class CullElasticSearchInstancesWithTerminationTag(packageName: String, stage: Stage, duration: Long)
  extends ASGTask {

  def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing) {
    for (instance <- asg.getInstances) {
      if (EC2.hasTag(instance, "Magenta", "Terminate")) {
        WaitForElasticSearchClusterGreen(packageName, stage, duration).execute(asg)
        val http = new Http()
        http((:/(EC2(instance).getPublicDnsName, 9200) / "_cluster" / "nodes" / "_local" / "_shutdown").POST >|)
        cull(asg, instance)
      }
    }
  }

  lazy val description = "Terminate instances with the termination tag for this deploy"
}


