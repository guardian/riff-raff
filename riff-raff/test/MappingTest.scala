package test

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import magenta._
import persistence._
import controllers.Logging

class MappingTest extends FlatSpec with ShouldMatchers with Utilities with PersistenceTestInstances with Logging {
  lazy val graters = new RiffRaffGraters {
    def loader = Some(getClass.getClassLoader)
  }

  "RecordConverter" should "transform a deploy record into a deploy document" in {
    RecordConverter(testRecord).deployDocument should be(
      DeployRecordDocument(
        testUUID,
        testTime,
        ParametersDocument("Tester", "Deploy", "test-project", "1", None, "CODE", "test-recipe", Nil),
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

  "LogDocumentTree" should "identify the root" in {
    val logDocuments = RecordConverter(testRecord).logDocuments
    val tree = LogDocumentTree(logDocuments)
    tree.roots.size should be(1)
    tree.roots.head match {
      case LogDocument(_, _, None, DeployDocument(),_) =>
      case _ => fail("Didn't get the expected document when trying to locate the root")
    }
  }

  it should "list children of a given node" in {
    val logDocuments = RecordConverter(testRecord).logDocuments
    val tree = LogDocumentTree(logDocuments)
    val children = tree.childrenOf(tree.roots.head)
    children should have size 2
  }

  it should "list parents of child nodes" in {
    val logDocuments = RecordConverter(testRecord).logDocuments
    val tree = LogDocumentTree(logDocuments)
    val children = tree.childrenOf(tree.roots.head)
    tree.parentOf(children.head) should be(Some(tree.roots.head))
  }

  "DocumentConverter" should "invert the action of RecordConverter" in {
    val converter = RecordConverter(testRecord)
    val record = DocumentConverter(converter.deployDocument, converter.logDocuments).deployRecord
    record should be(testRecord)
  }
}
