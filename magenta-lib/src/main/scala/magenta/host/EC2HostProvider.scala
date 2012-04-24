package magenta.host

import collection.JavaConversions._
import com.amazonaws.services.ec2.AmazonEC2Client
import magenta.tasks.AWS
import magenta.Host

trait HostProvider {
  def hosts: Seq[Host]
}

class EC2HostProvider extends HostProvider with EC2 {
  def instanceResponse = ec2.describeInstances

  def hosts = {
    for {
      res <- instanceResponse.getReservations
      instance <- res.getInstances
    } yield {
      Host(instance.getPublicDnsName)
    }
  }
}

trait EC2 extends AWS {
  lazy val ec2 = new AmazonEC2Client(credentials)
  ec2.setEndpoint("ec2.eu-west-1.amazonaws.com")
}
