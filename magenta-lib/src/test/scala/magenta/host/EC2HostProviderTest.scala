package magenta.host

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model.{Tag, Instance, Reservation, DescribeInstancesResult}
import magenta.App

class EC2HostProviderTest extends FlatSpec with ShouldMatchers with MockitoSugar {
  "EC2 host provider" should "use the public DNS as host name" in  {
    val provider = new EC2HostProvider with StubEC2
    val instanceResult = resultFor(new Instance().withPublicDnsName("gruyere"))
    when(provider.ec2.describeInstances()).thenReturn(instanceResult)
    
    provider.hosts.size should be (1)
    provider.hosts(0).name should be ("gruyere")
  }

  "EC2 host provider" should "get the stage from a tag" in  {
    val provider = new EC2HostProvider with StubEC2
    val instanceResult = resultFor(new Instance().withTags(new Tag("Stage", "PROD")))
    when(provider.ec2.describeInstances()).thenReturn(instanceResult)

    provider.hosts.size should be (1)
    provider.hosts(0).stage should be ("PROD")
  }

  "EC2 host provider" should "get the supported app from a tag" in  {
    val provider = new EC2HostProvider with StubEC2
    val instanceResult = resultFor(new Instance().withTags(new Tag("App", "ophan-tracker")))
    when(provider.ec2.describeInstances()).thenReturn(instanceResult)

    provider.hosts.size should be (1)
    provider.hosts(0).apps.head should be (App("ophan-tracker"))
  }

  def resultFor(instance: Instance) = new DescribeInstancesResult().withReservations(
    new Reservation().withInstances(instance))

  trait StubEC2 extends EC2 {
    override lazy val ec2 = mock[AmazonEC2]
  }
}
