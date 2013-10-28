package magenta
package resources

import org.joda.time.DateTime

trait Data {
  def keys: List[String]
  def all: Map[String,List[Datum]]
  def get(key:String): List[Datum] = all.get(key).getOrElse(Nil)
  def datum(key: String, app: App, stage: Stage): Option[Datum]
}

trait Instances {
  def all:List[Host]
  def get(app: App, stage: Stage):List[Host]
}

trait Lookup {
  def lastUpdated: DateTime
  def instances: Instances
  def data: Data
}