package test

import java.util.UUID
import controllers.Logging
import magenta.Message.Info
import magenta.Strategy.MostlyHarmless
import magenta._
import magenta.input.{DeploymentKey, DeploymentKeysSelector}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import persistence.{AllDocument, DeploymentKeysSelectorDocument}
import persistence.DeployDocument
import persistence._

class MappingTest
    extends AnyFlatSpec
    with Matchers
    with PersistenceTestInstances
    with Logging {
  "RecordConverter" should "transform a deploy record into a deploy document" in {
    RecordConverter(testRecord).deployDocument should be(
      DeployRecordDocument(
        testUUID,
        Some(testUUID.toString),
        testTime,
        ParametersDocument(
          "Tester",
          "test-project",
          "1",
          "CODE",
          Map("branch" -> "master"),
          AllDocument,
          Some(MostlyHarmless)
        ),
        RunState.Completed
      )
    )
  }

  it should "transform a deploy record into a set of log documents" in {
    val logDocuments = RecordConverter(testRecord).logDocuments
    logDocuments should have size 6
  }

  it should "build a set of log documents that are a valid tree" in {
    val logDocuments = RecordConverter(testRecord).logDocuments
    val tree = LogDocumentTree(logDocuments)
    tree.roots should have size 1

    val treeDocuments = tree.traverseTree(tree.roots.head)
    treeDocuments should have size logDocuments.size

    treeDocuments.toSet should be(logDocuments.toSet)
  }

  it should "transfer the deploy UUID into the log documents" in {
    val logDocuments = RecordConverter(testRecord).logDocuments
    logDocuments.foreach { doc =>
      doc.deploy should be(testRecord.uuid)
    }
  }

  it should "translate a deploy keys selector" in {
    val deployKeysSelector = DeploymentKeysSelector(
      List(
        DeploymentKey("testName", "actionOne", "stackOne", "testRegion"),
        DeploymentKey("testName", "actionTwo", "stackTwo", "testRegion")
      )
    )
    val convertedSelector = RecordConverter(
      testRecord.copy(parameters =
        parameters.copy(selector = deployKeysSelector)
      )
    ).deployDocument.parameters.selector
    convertedSelector shouldBe DeploymentKeysSelectorDocument(
      List(
        DeploymentKeyDocument(
          "testName",
          "actionOne",
          "stackOne",
          "testRegion"
        ),
        DeploymentKeyDocument("testName", "actionTwo", "stackTwo", "testRegion")
      )
    )
  }

  "LogDocumentTree" should "identify the root" in {
    val tree = LogDocumentTree(logDocuments)
    tree.roots.size should be(1)
    tree.roots.head match {
      case LogDocument(_, _, None, DeployDocument, _) =>
      case _                                          =>
        fail("Didn't get the expected document when trying to locate the root")
    }
  }

  it should "list children of a given node" in {
    val tree = LogDocumentTree(logDocuments)
    val children = tree.childrenOf(tree.roots.head)
    children should have size 2
  }

  it should "list parents of child nodes" in {
    val tree = LogDocumentTree(logDocuments)
    val children = tree.childrenOf(tree.roots.head)
    tree.parentOf(children.head) should be(Some(tree.roots.head))
  }

  "DocumentConverter" should "create a skeleton record from just a DeployRecordDocument" in {
    val deployRecordDocument = DeployRecordDocument(
      testUUID,
      Some(testUUID.toString),
      testTime,
      ParametersDocument(
        "test",
        "testProject",
        "test",
        "TEST",
        Map.empty,
        AllDocument,
        None
      ),
      RunState.Completed
    )
    val record = DocumentConverter(deployRecordDocument, Nil).deployRecord
    record.uuid should be(testUUID)
    record.time should be(testTime)
    record.parameters.deployer should be(Deployer("test"))
    record.recordState should be(Some(RunState.Completed))
  }

  it should "create a message wrapper" in {
    val id = UUID.randomUUID()
    val deployRecordDocument = DeployRecordDocument(
      testUUID,
      Some(testUUID.toString),
      testTime,
      ParametersDocument(
        "test",
        "testProject",
        "test",
        "TEST",
        Map.empty,
        AllDocument,
        None
      ),
      RunState.Completed
    )
    val logDocument = LogDocument(testUUID, id, None, Info("test"), testTime)
    val wrapper = DocumentConverter(
      deployRecordDocument,
      List(logDocument)
    ).deployRecord.messages.head
    wrapper.context.deployId should be(testUUID)
    wrapper.messageId should be(id)
  }

  it should "invert the action of RecordConverter" in {
    val converter = RecordConverter(testRecord)
    val record = DocumentConverter(
      converter.deployDocument,
      converter.logDocuments
    ).deployRecord
    record should be(testRecord.copy(recordState = Some(RunState.Completed)))
  }
}
