package test

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import deployment.{Task, DeployRecord}
import datastore.{RiffRaffGraters, MongoDatastore}
import magenta._
import java.util.UUID
import org.joda.time.DateTime
import tasks.Task
import deployment.Task
import java.io.File
import org.bson.BasicBSONEncoder

case class UnserialisableTask(unserialisableFile:File) extends Task {
  def execute(sshCredentials: KeyRing) {}
  val description = "A naughty and unserialisable task"
  val verbose = "Can't serialise me!"
  val taskHosts = List(Host("respub01"))
}

class MongoDatastoreTest extends FlatSpec with ShouldMatchers {

  val testTime = new DateTime()
  def stack( messages: Message * ): MessageStack = {
    stack(testTime, messages: _*)
  }
  def stack( time: DateTime, messages: Message * ): MessageStack = {
    MessageStack(messages.toList, time)
  }

  val parameters = DeployParameters(Deployer("Test"), Build("test","1"), Stage("TEST"))

  "MongoDatastore" should "use the correct name of the messageStack parameter on DeployRecord" in {
    val field = classOf[DeployRecord].getDeclaredField(MongoDatastore.MESSAGE_STACKS)
    field should not be null
    field.getType should be(classOf[List[MessageStack]])
  }

  val graters = new RiffRaffGraters {
    def loader = getClass.getClassLoader
  }

  "RiffRaffGrater" should "serialise a simple DeployRecord" in {
    val record = DeployRecord(Task.Deploy,UUID.randomUUID,parameters)
    val dbObject = graters.recordGrater.asDBObject(record)
    dbObject should not be null
    val encoder = new BasicBSONEncoder()
    val bytes = encoder.encode(dbObject)
    bytes should not be null
  }

  it should "serialise a DeployRecord containing exceptions" in {
    val uuid = UUID.randomUUID
    val exception = new RuntimeException()
    val record = DeployRecord(testTime, Task.Deploy, uuid, parameters, List(stack(Fail("Fail", exception))))
    val dbObject = graters.recordGrater.asDBObject(record)
    dbObject should not be null
    val encoder = new BasicBSONEncoder()
    val bytes = encoder.encode(dbObject)
    bytes should not be null
  }

  it should "serialise a DeployRecord containing tasks" in {
    val uuid = UUID.randomUUID
    val tasks = List( UnserialisableTask(new File("/tmp")))
    val record = DeployRecord(testTime, Task.Deploy, uuid, parameters, List(stack(TaskList(tasks))))
    val dbObject = graters.recordGrater.asDBObject(record)
    dbObject should not be null
    val encoder = new BasicBSONEncoder()
    val bytes = encoder.encode(dbObject)
    bytes should not be null
  }

}