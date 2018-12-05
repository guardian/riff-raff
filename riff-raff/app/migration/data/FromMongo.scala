package migration
package data

import org.mongodb.scala.{ Document => MDocument }

trait FromMongo[A] {
  def parseMongo(obj: MDocument): Option[A]
}

object FromMongo {
  def apply[A](implicit F: FromMongo[A]): FromMongo[A] = F
}

