package magenta.host

import collection.JavaConversions._
import magenta.tasks.AWS
import magenta.Host
import com.amazonaws.services.ec2.{AmazonEC2, AmazonEC2Client}
import com.amazonaws.services.ec2.model.Instance

trait HostProvider {
  def hosts: Seq[Host]
}

class EC2HostProvider extends HostProvider with EC2 {
  def instanceResponse = ec2.describeInstances

  def tagValue(instance: Instance, key: String) = instance.getTags.find(_.getKey == key).map(_.getValue).getOrElse("")

  def hosts = {
    for {
      res <- instanceResponse.getReservations
      instance <- res.getInstances
    } yield {
      Host(instance.getPublicDnsName, stage = tagValue(instance, "Stage")).app(tagValue(instance, "App"))
    }
  }
}

trait EC2 extends AWS {
  lazy val ec2 : AmazonEC2 = new AmazonEC2Client(credentials)
  ec2.setEndpoint("ec2.eu-west-1.amazonaws.com")
}
