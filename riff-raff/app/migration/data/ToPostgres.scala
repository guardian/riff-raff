package migration
package data

import io.circe.Json
import scalikejdbc._

trait ToPostgres[A] {
  type K
  def key(a: A): K
  def json(a: A): Json
  def tableName: String
  def id: String
  def drop: SQL[Nothing, NoExtractor]
  def create: SQL[Nothing, NoExtractor]
  def insert(key: K, json: String): SQL[Nothing, NoExtractor]
}

object ToPostgres {
  def apply[A](implicit T: ToPostgres[A]): ToPostgres[A] = T
}