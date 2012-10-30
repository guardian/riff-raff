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
import com.mongodb.util.JSON
import com.mongodb.casbah.commons.ValidBSONType.DBObject
import com.mongodb.DBObject
import com.mongodb.casbah.commons.MongoDBObject

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
    def loader = Some(getClass.getClassLoader)
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

  "Serialised case classes" should "not change" in {
    // if this test fails then you have made a breaking change to the database model - don't just fix this!
    val dataModelDump = """{ "_typeHint" : "deployment.DeployRecord" , "time" : { "$date" : "2012-11-08T17:20:00.000Z"} , "taskType" : "Deploy" , "_id" : { "$uuid" : "39320f5b-7837-4f47-85f7-bc2d780e19f6"} , "parameters" : { "_typeHint" : "magenta.DeployParameters" , "deployer" : { "_typeHint" : "magenta.Deployer" , "name" : "Tester"} , "build" : { "_typeHint" : "magenta.Build" , "projectName" : "test::project" , "id" : "1"} , "stage" : { "_typeHint" : "magenta.Stage" , "name" : "TEST"} , "recipe" : { "_typeHint" : "magenta.RecipeName" , "name" : "test-recipe"} , "hostList" : [ "testhost1" , "testhost2"]} , "messageStacks" : [ { "_typeHint" : "magenta.MessageStack" , "messages" : [ { "_typeHint" : "magenta.StartContext" , "originalMessage" : { "_typeHint" : "magenta.Deploy" , "parameters" : { "_typeHint" : "magenta.DeployParameters" , "deployer" : { "_typeHint" : "magenta.Deployer" , "name" : "Tester"} , "build" : { "_typeHint" : "magenta.Build" , "projectName" : "test::project" , "id" : "1"} , "stage" : { "_typeHint" : "magenta.Stage" , "name" : "TEST"} , "recipe" : { "_typeHint" : "magenta.RecipeName" , "name" : "test-recipe"} , "hostList" : [ "testhost1" , "testhost2"]}}}] , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}} , { "_typeHint" : "magenta.MessageStack" , "messages" : [ { "_typeHint" : "magenta.Info" , "text" : "An information message"} , { "_typeHint" : "magenta.Verbose" , "text" : "A verbose message"} , { "_typeHint" : "magenta.CommandOutput" , "text" : "Some command stdout"} , { "_typeHint" : "magenta.CommandError" , "text" : "Some command stderr"} , { "_typeHint" : "magenta.Fail" , "text" : "A failure" , "detail" : { "_typeHint" : "magenta.ThrowableDetail" , "name" : "java.lang.RuntimeException" , "message" : "Test Exception" , "stackTrace" : "Long string\n With new lines\n and line numbers:5\n etc etc etc" , "cause" : { "_typeHint" : "magenta.ThrowableDetail" , "name" : "java.lang.RuntimeException" , "message" : "Test nested exception" , "stackTrace" : "Long string\n With new lines\n and line numbers:5\n etc etc etc"}}} , { "_typeHint" : "magenta.TaskRun" , "task" : { "_typeHint" : "magenta.TaskDetail" , "name" : "UnserialisableTask" , "description" : "A naughty and unserialisable task" , "verbose" : "Can't serialise me!" , "taskHosts" : [ { "_typeHint" : "magenta.Host" , "name" : "respub01" , "apps" : [ ] , "stage" : "NO_STAGE"}]}} , { "_typeHint" : "magenta.TaskList" , "taskList" : [ { "_typeHint" : "magenta.TaskDetail" , "name" : "UnserialisableTask" , "description" : "A naughty and unserialisable task" , "verbose" : "Can't serialise me!" , "taskHosts" : [ { "_typeHint" : "magenta.Host" , "name" : "respub01" , "apps" : [ ] , "stage" : "NO_STAGE"}]}]}] , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}} , { "_typeHint" : "magenta.MessageStack" , "messages" : [ { "_typeHint" : "magenta.FailContext" , "originalMessage" : { "_typeHint" : "magenta.Deploy" , "parameters" : { "_typeHint" : "magenta.DeployParameters" , "deployer" : { "_typeHint" : "magenta.Deployer" , "name" : "Tester"} , "build" : { "_typeHint" : "magenta.Build" , "projectName" : "test::project" , "id" : "1"} , "stage" : { "_typeHint" : "magenta.Stage" , "name" : "TEST"} , "recipe" : { "_typeHint" : "magenta.RecipeName" , "name" : "test-recipe"} , "hostList" : [ "testhost1" , "testhost2"]}} , "detail" : { "_typeHint" : "magenta.ThrowableDetail" , "name" : "java.lang.RuntimeException" , "message" : "Test Exception" , "stackTrace" : "Long string\n With new lines\n and line numbers:5\n etc etc etc" , "cause" : { "_typeHint" : "magenta.ThrowableDetail" , "name" : "java.lang.RuntimeException" , "message" : "Test nested exception" , "stackTrace" : "Long string\n With new lines\n and line numbers:5\n etc etc etc"}}}] , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}} , { "_typeHint" : "magenta.MessageStack" , "messages" : [ { "_typeHint" : "magenta.FinishContext" , "originalMessage" : { "_typeHint" : "magenta.Deploy" , "parameters" : { "_typeHint" : "magenta.DeployParameters" , "deployer" : { "_typeHint" : "magenta.Deployer" , "name" : "Tester"} , "build" : { "_typeHint" : "magenta.Build" , "projectName" : "test::project" , "id" : "1"} , "stage" : { "_typeHint" : "magenta.Stage" , "name" : "TEST"} , "recipe" : { "_typeHint" : "magenta.RecipeName" , "name" : "test-recipe"} , "hostList" : [ "testhost1" , "testhost2"]}}}] , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}]}"""

    val time = new DateTime(2012,11,8,17,20,00)
    val uuid = UUID.fromString("39320f5b-7837-4f47-85f7-bc2d780e19f6")
    val parameters = DeployParameters(Deployer("Tester"), Build("test::project", "1"), Stage("TEST"), RecipeName("test-recipe"), List("testhost1", "testhost2"))
    val testNestedDetail = ThrowableDetail("java.lang.RuntimeException", "Test nested exception", "Long string\n With new lines\n and line numbers:5\n etc etc etc")
    val testThrowableDetail = ThrowableDetail("java.lang.RuntimeException", "Test Exception", "Long string\n With new lines\n and line numbers:5\n etc etc etc", Some(testNestedDetail))
    val testTask = UnserialisableTask(new File("/tmp"))
    val messageStacks = List(
      MessageStack(List(StartContext(Deploy(parameters))), time),
      MessageStack(List(
        Info("An information message"),
        Verbose("A verbose message"),
        CommandOutput("Some command stdout"),
        CommandError("Some command stderr"),
        Fail("A failure", testThrowableDetail),
        TaskRun(testTask),
        TaskList(List(testTask))
      ), time),
      MessageStack(List(FailContext(Deploy(parameters), testThrowableDetail)), time),
      MessageStack(List(FinishContext(Deploy(parameters))), time)
    )
    val deployRecord = DeployRecord(time, Task.Deploy, uuid, parameters, messageStacks)
    val gratedDeployRecord = graters.recordGrater.asDBObject(deployRecord)

    val jsonDeployRecord = JSON.serialize(gratedDeployRecord)
    jsonDeployRecord should be(dataModelDump)

    val ungratedDBObject = JSON.parse(dataModelDump).asInstanceOf[DBObject]
    ungratedDBObject.toString should be(dataModelDump)

    val mongoDbObject = new MongoDBObject(ungratedDBObject)

    val ungratedDeployRecord = graters.recordGrater.asObject(mongoDbObject)
    ungratedDeployRecord should be(deployRecord)
  }

}