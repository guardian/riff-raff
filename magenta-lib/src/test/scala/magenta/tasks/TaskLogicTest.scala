package magenta.tasks

import org.scalatest.FlatSpec
import magenta.tasks.CullElasticSearchInstancesWithTerminationTag
import Ordering.Implicits._
import org.scalatest._

class TasksLogicTest extends FlatSpec {
    "applyInOrder method" should "apply method in correct order" in {
        var resultsList = List[Int]()
        def addToResultsList(x: Int) = (resultsList = resultsList :+ x)

        CullElasticSearchInstancesWithTerminationTag.applyInOrder(
            List(List(1,2,3), List(4,5,6), List(7,8,9)), addToResultsList)

        assert(resultsList == List(1, 4, 7, 2, 5, 8, 3, 6, 9))
    }

    "applyInOrder method" should "work for lists of different lengths" in {
        var resultsList = List[Int]()
        def addToResultsList(x: Int) = (resultsList = resultsList :+ x)

        CullElasticSearchInstancesWithTerminationTag.applyInOrder(
            List(List(1), List(4,5,6), List(7,8)), addToResultsList)
        
        assert(resultsList == List(1, 4, 7, 5, 8, 6))
    }
}
