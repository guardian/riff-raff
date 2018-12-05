package migration
package data

import io.circe.Json

trait ToPostgres[A] {
  type K
  def key(a: A): K
  def json(a: A): Json
}

object ToPostgres {
  def apply[A](implicit T: ToPostgres[A]): ToPostgres[A] = T
}