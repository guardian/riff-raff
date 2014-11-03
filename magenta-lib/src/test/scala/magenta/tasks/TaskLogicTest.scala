package magenta.tasks

import org.scalatest.FlatSpec
import magenta.tasks.CullElasticSearchInstancesWithTerminationTag
import Ordering.Implicits._
import org.scalatest._

class TasksLogicTest extends FlatSpec {
    "transposeLists" should "correctly transpose lists" in {
        val resultsList = CullElasticSearchInstancesWithTerminationTag.transposeLists(
            List(List(1,2,3), List(4,5,6), List(7,8,9)))

        assert(resultsList == List(1, 4, 7, 2, 5, 8, 3, 6, 9))
    }

    it should "work for lists of different lengths" in {
        val resultsList = CullElasticSearchInstancesWithTerminationTag.transposeLists(
            List(List(1), List(4,5,6), List(7,8)))
        
        assert(resultsList == List(1, 4, 7, 5, 8, 6))
    }
}
