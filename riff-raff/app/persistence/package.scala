package persistence

import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.Imports._
import com.mongodb.DBObject
import com.mongodb.casbah.MongoCursor
import deployment.{DeployFilter, PaginationView}
import magenta._
import org.joda.time.LocalDate

object `package` {
  implicit class deployFilter2Criteria(filter: DeployFilter) {
    def criteria: DBObject = {
      val criteriaList: List[(String, Any)] = Nil ++
        filter.projectName.map(p => ("parameters.projectName", s"(?i)$p".r)) ++
        filter.stage.map(("parameters.stage", _)) ++
        filter.deployer.map(("parameters.deployer", _)) ++
        filter.status.map(s => ("status", s.toString)) ++
        filter.hasWarnings.map(hw => ("hasWarnings", hw))
      filter.maxDaysAgo match {
        case None => MongoDBObject(criteriaList)

        case Some(days) => MongoDBObject(criteriaList) ++ ("startTime" $gt LocalDate.now.minusDays(days).toDate)
      }
    }
  }

  implicit class mongoCursor2pagination(cursor: MongoCursor) {
    def pagination(p: PaginationView): MongoCursor = {
      p.pageSize.map(l => cursor.skip(p.skip.get).limit(l)).getOrElse(cursor)
    }
  }

  implicit class message2MessageDocument(message: Message) {
    def asMessageDocument: MessageDocument = MessageDocument(message)
  }

  implicit class richList[T](list: List[T]) {
    def distinctOn[N](f: T => N): List[T] = {
      list.foldLeft((Set.empty[N], List.empty[T])){ case ((distinctElements, acc), item) =>
          val element = f(item)
          if (distinctElements.contains(element)) {
            distinctElements -> acc
          } else {
            (distinctElements + element) -> (item :: acc)
          }
      }._2.reverse
    }
  }
}