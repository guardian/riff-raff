package migration
package data

import io.circe.Json

trait ToPostgre[A] {
  type K
  def key(a: A): K
  def json(a: A): Json
}

object ToPostgre {
  def apply[A](implicit T: ToPostgre[A]): ToPostgre[A] = T
}