package test

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import persistence._
import org.bson.BasicBSONEncoder
import deployment.{Task, DeployRecord}
import org.joda.time.DateTime
import java.util.UUID
import java.io.File
import com.mongodb.util.JSON
import com.mongodb.DBObject
import com.mongodb.casbah.commons.MongoDBObject
import magenta._


class RepresentationTest extends FlatSpec with ShouldMatchers with Utilities {

  "MessageDocument" should "convert from log messages to documents" in {
    deploy.asMessageDocument should be(DeployDocument())
    infoMsg.asMessageDocument should be(InfoDocument("$ echo hello"))
    cmdOut.asMessageDocument should be(CommandOutputDocument("hello"))
    verbose.asMessageDocument should be(VerboseDocument("return value 0"))
    finishDep.asMessageDocument should be(FinishContextDocument())
    finishInfo.asMessageDocument should be(FinishContextDocument())
    failInfo.asMessageDocument should be(FailContextDocument(failInfo.detail))
    failDep.asMessageDocument should be(FailContextDocument(failDep.detail))
  }

  it should "not convert StartContext log messages" in {
    intercept[IllegalArgumentException]{
      startDeploy.asMessageDocument
    }
  }

  "LogDocument" should "serialise all message types to BSON" in {
    val messages = Seq(deploy, infoMsg, cmdOut, verbose, finishDep, finishInfo, failInfo, failDep)
    val documents = messages.map(LogDocument(testUUID, "test", Some("test"), _, testTime))
    documents.foreach{ document =>
      val dbObject = graters.logDocumentGrater.asDBObject(document)
      dbObject should not be null
      val encoder = new BasicBSONEncoder()
      val bytes = encoder.encode(dbObject)
      bytes should not be null
    }
  }

  it should "not change without careful thought and testing of migration" in {
    val time = new DateTime(2012,11,8,17,20,00)
    val messageJsonMap = Map(
      deploy -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : "test" , "parent" : "test" , "document" : { "_typeHint" : "persistence.DeployDocument"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      infoMsg -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : "test" , "parent" : "test" , "document" : { "_typeHint" : "persistence.InfoDocument" , "text" : "$ echo hello"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      cmdOut -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : "test" , "parent" : "test" , "document" : { "_typeHint" : "persistence.CommandOutputDocument" , "text" : "hello"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      verbose -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : "test" , "parent" : "test" , "document" : { "_typeHint" : "persistence.VerboseDocument" , "text" : "return value 0"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      finishDep -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : "test" , "parent" : "test" , "document" : { "_typeHint" : "persistence.FinishContextDocument"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      finishInfo -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : "test" , "parent" : "test" , "document" : { "_typeHint" : "persistence.FinishContextDocument"} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      failInfo -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : "test" , "parent" : "test" , "document" : { "_typeHint" : "persistence.FailContextDocument" , "detail" : { "name" : "java.lang.RuntimeException" , "message" : "Failed" , "stackTrace" : "test.RepresentationTest.<init>(RepresentationTest.scala:184)\nsun.reflect.NativeConstructorAccessorImpl.newInstance0(Native Method)\nsun.reflect.NativeConstructorAccessorImpl.newInstance(NativeConstructorAccessorImpl.java:39)\nsun.reflect.DelegatingConstructorAccessorImpl.newInstance(DelegatingConstructorAccessorImpl.java:27)\njava.lang.reflect.Constructor.newInstance(Constructor.java:513)\njava.lang.Class.newInstance0(Class.java:355)\njava.lang.Class.newInstance(Class.java:308)\norg.scalatest.tools.ScalaTestFramework$ScalaTestRunner.run(ScalaTestFramework.scala:141)\nsbt.TestRunner.delegateRun(TestFramework.scala:62)\nsbt.TestRunner.run(TestFramework.scala:56)\nsbt.TestRunner.runTest$1(TestFramework.scala:76)\nsbt.TestRunner.run(TestFramework.scala:85)\nsbt.TestFramework$$anonfun$6$$anonfun$apply$8$$anonfun$7$$anonfun$apply$9.apply(TestFramework.scala:184)\nsbt.TestFramework$$anonfun$6$$anonfun$apply$8$$anonfun$7$$anonfun$apply$9.apply(TestFramework.scala:184)\nsbt.TestFramework$.sbt$TestFramework$$withContextLoader(TestFramework.scala:196)\nsbt.TestFramework$$anonfun$6$$anonfun$apply$8$$anonfun$7.apply(TestFramework.scala:184)\nsbt.TestFramework$$anonfun$6$$anonfun$apply$8$$anonfun$7.apply(TestFramework.scala:184)\nsbt.Tests$$anonfun$makeSerial$1$$anonfun$apply$8.apply(Tests.scala:115)\nsbt.Tests$$anonfun$makeSerial$1$$anonfun$apply$8.apply(Tests.scala:115)\nscala.collection.TraversableLike$$anonfun$map$1.apply(TraversableLike.scala:194)\nscala.collection.TraversableLike$$anonfun$map$1.apply(TraversableLike.scala:194)\nscala.collection.LinearSeqOptimized$class.foreach(LinearSeqOptimized.scala:59)\nscala.collection.immutable.List.foreach(List.scala:45)\nscala.collection.TraversableLike$class.map(TraversableLike.scala:194)\nscala.collection.immutable.List.map(List.scala:45)\nsbt.Tests$$anonfun$makeSerial$1.apply(Tests.scala:115)\nsbt.Tests$$anonfun$makeSerial$1.apply(Tests.scala:115)\nsbt.std.Transform$$anon$3$$anonfun$apply$2.apply(System.scala:47)\nsbt.std.Transform$$anon$3$$anonfun$apply$2.apply(System.scala:47)\nsbt.std.Transform$$anon$5.work(System.scala:67)\nsbt.Execute$$anonfun$submit$1$$anonfun$apply$1.apply(Execute.scala:221)\nsbt.Execute$$anonfun$submit$1$$anonfun$apply$1.apply(Execute.scala:221)\nsbt.ErrorHandling$.wideConvert(ErrorHandling.scala:18)\nsbt.Execute.work(Execute.scala:227)\nsbt.Execute$$anonfun$submit$1.apply(Execute.scala:221)\nsbt.Execute$$anonfun$submit$1.apply(Execute.scala:221)\nsbt.CompletionService$$anon$1$$anon$2.call(CompletionService.scala:26)\njava.util.concurrent.FutureTask$Sync.innerRun(FutureTask.java:303)\njava.util.concurrent.FutureTask.run(FutureTask.java:138)\njava.util.concurrent.Executors$RunnableAdapter.call(Executors.java:441)\njava.util.concurrent.FutureTask$Sync.innerRun(FutureTask.java:303)\njava.util.concurrent.FutureTask.run(FutureTask.java:138)\njava.util.concurrent.ThreadPoolExecutor$Worker.runTask(ThreadPoolExecutor.java:886)\njava.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:908)\njava.lang.Thread.run(Thread.java:662)\n"}} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}""",
      failDep -> """{ "deploy" : { "$uuid" : "90013e69-8afc-4ba2-80a8-d7b063183d13"} , "id" : "test" , "parent" : "test" , "document" : { "_typeHint" : "persistence.FailContextDocument" , "detail" : { "name" : "java.lang.RuntimeException" , "message" : "Failed" , "stackTrace" : "test.RepresentationTest.<init>(RepresentationTest.scala:185)\nsun.reflect.NativeConstructorAccessorImpl.newInstance0(Native Method)\nsun.reflect.NativeConstructorAccessorImpl.newInstance(NativeConstructorAccessorImpl.java:39)\nsun.reflect.DelegatingConstructorAccessorImpl.newInstance(DelegatingConstructorAccessorImpl.java:27)\njava.lang.reflect.Constructor.newInstance(Constructor.java:513)\njava.lang.Class.newInstance0(Class.java:355)\njava.lang.Class.newInstance(Class.java:308)\norg.scalatest.tools.ScalaTestFramework$ScalaTestRunner.run(ScalaTestFramework.scala:141)\nsbt.TestRunner.delegateRun(TestFramework.scala:62)\nsbt.TestRunner.run(TestFramework.scala:56)\nsbt.TestRunner.runTest$1(TestFramework.scala:76)\nsbt.TestRunner.run(TestFramework.scala:85)\nsbt.TestFramework$$anonfun$6$$anonfun$apply$8$$anonfun$7$$anonfun$apply$9.apply(TestFramework.scala:184)\nsbt.TestFramework$$anonfun$6$$anonfun$apply$8$$anonfun$7$$anonfun$apply$9.apply(TestFramework.scala:184)\nsbt.TestFramework$.sbt$TestFramework$$withContextLoader(TestFramework.scala:196)\nsbt.TestFramework$$anonfun$6$$anonfun$apply$8$$anonfun$7.apply(TestFramework.scala:184)\nsbt.TestFramework$$anonfun$6$$anonfun$apply$8$$anonfun$7.apply(TestFramework.scala:184)\nsbt.Tests$$anonfun$makeSerial$1$$anonfun$apply$8.apply(Tests.scala:115)\nsbt.Tests$$anonfun$makeSerial$1$$anonfun$apply$8.apply(Tests.scala:115)\nscala.collection.TraversableLike$$anonfun$map$1.apply(TraversableLike.scala:194)\nscala.collection.TraversableLike$$anonfun$map$1.apply(TraversableLike.scala:194)\nscala.collection.LinearSeqOptimized$class.foreach(LinearSeqOptimized.scala:59)\nscala.collection.immutable.List.foreach(List.scala:45)\nscala.collection.TraversableLike$class.map(TraversableLike.scala:194)\nscala.collection.immutable.List.map(List.scala:45)\nsbt.Tests$$anonfun$makeSerial$1.apply(Tests.scala:115)\nsbt.Tests$$anonfun$makeSerial$1.apply(Tests.scala:115)\nsbt.std.Transform$$anon$3$$anonfun$apply$2.apply(System.scala:47)\nsbt.std.Transform$$anon$3$$anonfun$apply$2.apply(System.scala:47)\nsbt.std.Transform$$anon$5.work(System.scala:67)\nsbt.Execute$$anonfun$submit$1$$anonfun$apply$1.apply(Execute.scala:221)\nsbt.Execute$$anonfun$submit$1$$anonfun$apply$1.apply(Execute.scala:221)\nsbt.ErrorHandling$.wideConvert(ErrorHandling.scala:18)\nsbt.Execute.work(Execute.scala:227)\nsbt.Execute$$anonfun$submit$1.apply(Execute.scala:221)\nsbt.Execute$$anonfun$submit$1.apply(Execute.scala:221)\nsbt.CompletionService$$anon$1$$anon$2.call(CompletionService.scala:26)\njava.util.concurrent.FutureTask$Sync.innerRun(FutureTask.java:303)\njava.util.concurrent.FutureTask.run(FutureTask.java:138)\njava.util.concurrent.Executors$RunnableAdapter.call(Executors.java:441)\njava.util.concurrent.FutureTask$Sync.innerRun(FutureTask.java:303)\njava.util.concurrent.FutureTask.run(FutureTask.java:138)\njava.util.concurrent.ThreadPoolExecutor$Worker.runTask(ThreadPoolExecutor.java:886)\njava.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:908)\njava.lang.Thread.run(Thread.java:662)\n"}} , "time" : { "$date" : "2012-11-08T17:20:00.000Z"}}"""
    )
    messageJsonMap.foreach { case (message, json) =>
      val logDocument = LogDocument(testUUID, "test", Some("test"), message, time)

      val gratedDocument = graters.logDocumentGrater.asDBObject(logDocument)
      val jsonLogDocument = JSON.serialize(gratedDocument)

      val diff = compareJson(json, jsonLogDocument)

      if (json.isEmpty) {
        jsonLogDocument should be(json)
      } else {
        diff.toString should be("")
        jsonLogDocument should be(json)
      }

      val ungratedDBObject = JSON.parse(json).asInstanceOf[DBObject]
      ungratedDBObject.toString should be(json)

      val ungratedDeployDocument = graters.logDocumentGrater.asObject(new MongoDBObject(ungratedDBObject))
      ungratedDeployDocument should be(logDocument)
    }
  }

  "LogDocumentTree" should "identify the root" in {
    pending
  }

  it should "list children of a given node" in {
    pending
  }

  it should "list parents of child nodes" in {
    pending
  }


  "DeployRecordDocument" should "build from a deploy record" in {
    testDocument should be(
      DeployRecordDocument(
        testUUID,
        testTime,
        ParametersDocument("Tester", "Deploy", "test-project", "1", None, "CODE", "test-recipe", Nil),
        RunState.Completed
      )
    )
  }

  it should "serialise to BSON" in {
    val dbObject = graters.deployGrater.asDBObject(testDocument)
    dbObject should not be null
    val encoder = new BasicBSONEncoder()
    val bytes = encoder.encode(dbObject)
    bytes should not be null
  }

  it should "never change without careful thought and testing of migration" in {
    val dataModelDump = """{ "_id" : { "$uuid" : "39320f5b-7837-4f47-85f7-bc2d780e19f6"} , "startTime" : { "$date" : "2012-11-08T17:20:00.000Z"} , "parameters" : { "deployer" : "Tester" , "deployType" : "Deploy" , "projectName" : "test::project" , "buildId" : "1" , "stage" : "TEST" , "recipe" : "test-recipe" , "hostList" : [ "testhost1" , "testhost2"]} , "status" : "Completed"}"""

    val deployDocument = DeployRecordDocument(comprehensiveDeployRecord)
    val gratedDeployDocument = graters.deployGrater.asDBObject(deployDocument)

    val jsonDeployDocument = JSON.serialize(gratedDeployDocument)
    val diff = compareJson(dataModelDump, jsonDeployDocument)
    diff.toString should be("")
    jsonDeployDocument should be(dataModelDump)

    val ungratedDBObject = JSON.parse(dataModelDump).asInstanceOf[DBObject]
    ungratedDBObject.toString should be(dataModelDump)

    val ungratedDeployDocument = graters.deployGrater.asObject(new MongoDBObject(ungratedDBObject))
    ungratedDeployDocument should be(deployDocument)
  }

  lazy val graters = new DocumentGraters {
    def loader = Some(getClass.getClassLoader)
  }

  val testTime = new DateTime()
  lazy val testUUID = UUID.fromString("90013e69-8afc-4ba2-80a8-d7b063183d13")
  lazy val testParams = DeployParameters(Deployer("Tester"), Build("test-project", "1"), Stage("CODE"), RecipeName("test-recipe"))
  lazy val testParamsWithHosts = testParams.copy(hostList=List("host1", "host2"))
  lazy val testRecord = DeployRecord(testTime, Task.Deploy, testUUID, testParams, messageStacks)
  lazy val testDocument = DeployRecordDocument(testRecord)

  lazy val comprehensiveDeployRecord = {
    val time = new DateTime(2012,11,8,17,20,00)
    val uuid = UUID.fromString("39320f5b-7837-4f47-85f7-bc2d780e19f6")
    val parameters = DeployParameters(Deployer("Tester"), Build("test::project", "1"), Stage("TEST"), RecipeName("test-recipe"), List("testhost1", "testhost2"))
    val testNestedDetail = ThrowableDetail("java.lang.RuntimeException", "Test nested exception", "Long string\n With new lines\n and line numbers:5\n etc etc etc")
    val testThrowableDetail = ThrowableDetail("java.lang.RuntimeException", "Test Exception", "Long string\n With new lines\n and line numbers:5\n etc etc etc", Some(testNestedDetail))
    val testTask = UnserialisableTask(new File("/tmp"))
    val messageStacks = List(
      MessageStack(List(StartContext(Deploy(parameters))), time),
      MessageStack(List(Info("An information message"), StartContext(Deploy(parameters))), time),
      MessageStack(List(Verbose("A verbose message"), StartContext(Deploy(parameters))), time),
      MessageStack(List(CommandOutput("Some command stdout"), StartContext(Deploy(parameters))), time),
      MessageStack(List(CommandError("Some command stderr"), StartContext(Deploy(parameters))), time),
      MessageStack(List(Fail("A failure", testThrowableDetail), StartContext(Deploy(parameters))), time),
      MessageStack(List(TaskRun(testTask), StartContext(Deploy(parameters))), time),
      MessageStack(List(TaskList(List(testTask)), StartContext(Deploy(parameters))), time),
      MessageStack(List(FailContext(Deploy(parameters), testThrowableDetail)), time),
      MessageStack(List(FinishContext(Deploy(parameters))), time)
    )
    DeployRecord(time, Task.Deploy, uuid, parameters, messageStacks)
  }

  def stack( messages: Message * ): MessageStack = {
    stack(testTime, messages: _*)
  }

  def stack( time: DateTime, messages: Message * ): MessageStack = {
    MessageStack(messages.toList, time)
  }

  val parameters = DeployParameters(Deployer("Test reports"),Build("test-project","1"),Stage("CODE"))

  val deploy = Deploy(parameters)
  val startDeploy = StartContext(deploy)
  val infoMsg = Info("$ echo hello")
  val startInfo = StartContext(infoMsg)
  val cmdOut = CommandOutput("hello")
  val verbose = Verbose("return value 0")
  val finishDep = FinishContext(deploy)
  val finishInfo = FinishContext(infoMsg)
  val failInfo = FailContext(infoMsg, new RuntimeException("Failed"))
  val failDep = FailContext(deploy, new RuntimeException("Failed"))
  val messageStacks: List[MessageStack] =
    stack(startDeploy) ::
      stack(startInfo, deploy) ::
      stack(cmdOut, infoMsg, deploy) ::
      stack(verbose, infoMsg, deploy) ::
      stack(finishInfo, deploy) ::
      stack(finishDep) ::
      Nil


}
