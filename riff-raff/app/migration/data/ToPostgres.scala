package migration
package data

import play.api.libs.json.JsValue
import scalikejdbc._

trait ToPostgres[A] {
  type K
  def key(a: A): K
  def json(a: A): JsValue
  def tableName: String
  def id: String
  def drop: SQL[Nothing, NoExtractor]
  def create: SQL[Nothing, NoExtractor]
  def insert(key: K, json: String): SQL[Nothing, NoExtractor]
}

object ToPostgres {
  def apply[A](implicit T: ToPostgres[A]): ToPostgres[A] = T
}