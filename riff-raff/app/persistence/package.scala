package persistence

import deployment.{DeployFilter, PaginationView}
import magenta._
import org.joda.time.LocalDate
import scalikejdbc._
import scalikejdbc.interpolation.SQLSyntax

object `package` {
  implicit class deployFilter2Criteria(filter: DeployFilter) {
    def postgresFilters: SQLSyntax = {
      val filters = filter.sqlParams
      if (filters.isEmpty) sqls""
      else {
        val andFilters =
          filters.tail.foldLeft(filters.head)((acc, f) => sqls"$acc AND $f")
        sqls"WHERE $andFilters"
      }
    }
  }

  implicit class message2MessageDocument(message: Message) {
    def asMessageDocument: MessageDocument = MessageDocument(message)
  }

  implicit class richList[T](list: List[T]) {
    def distinctOn[N](f: T => N): List[T] = {
      list
        .foldRight((Set.empty[N], List.empty[T])) {
          case (item, (distinctElements, acc)) =>
            val element = f(item)
            if (distinctElements.contains(element)) {
              distinctElements -> acc
            } else {
              (distinctElements + element) -> (item :: acc)
            }
        }
        ._2
    }
  }
}
