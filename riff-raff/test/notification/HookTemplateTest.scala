package notification

import java.util.UUID

import magenta.RunState
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import persistence.{DeployRecordDocument, ParametersDocument}

import scala.util.{Success, Try}

class HookTemplateTest extends FunSuite with Matchers {
  test("HookTemplate should leave unparameterised template as is") {
    val template = new HookTemplate("foo", record)
    val res: Try[String] = template.Template.run()
    res should be (Success("foo"))
  }

  test("HookTemplate should replace projectName in template") {
    val template = new HookTemplate("{'name': '%deploy.project%'}", record)
    val res: Try[String] = template.Template.run()
    res should be (Success("{'name': 'A Project'}"))
  }

  test("HookTemplate should leave as is an input with a single %") {
    val template = new HookTemplate("{'name': '%deploy.project'}", record)
    val res: Try[String] = template.Template.run()
    res should be (Success("{'name': '%deploy.project'}"))
  }

  test("HookTemplate should replace tags if they exist") {
    val template = new HookTemplate("{'blah': '%deploy.tag.foo%'}", record)
    val res: Try[String] = template.Template.run()
    res should be (Success("{'blah': 'bar'}"))
  }

  test("HookTemplate should replace unknown tag with an empty string") {
    val template = new HookTemplate("{'blah': '%deploy.tag.foo%'}", record)
    val res: Try[String] = template.Template.run()
    res should be (Success("{'blah': 'bar'}"))
  }

  test("Should url escape params if so configured") {
    val template = new HookTemplate("http://localhost:80/test?project=%deploy.project%",
      record.copy(parameters = paramsDoc.copy(projectName = "test::project")), urlEncode = true)
    val res: Try[String] = template.Template.run()
    res should be (Success("http://localhost:80/test?project=test%3A%3Aproject"))
  }

  val paramsDoc = ParametersDocument(
    deployer = "Some One",
    projectName = "A Project",
    buildId = "a123",
    stage = "PROD",
    recipe = "default",
    stacks = Nil,
    hostList = Nil,
    tags = Map("foo" -> "bar")
  )
  val uuid = UUID.randomUUID()
  val record = DeployRecordDocument(uuid, Some(uuid.toString), DateTime.now(), paramsDoc, RunState.Completed)
}
