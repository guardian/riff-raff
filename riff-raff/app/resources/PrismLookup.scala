package resources

import magenta._
import org.joda.time.DateTime
import play.api.libs.ws.WS
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import utils.Json._

object PrismLookup extends Lookup with MagentaCredentials {
  object prism {
    val url = conf.Configuration.lookup.prismUrl
    def get[T](path: String)(block: JsValue => T): T = {
      val result = WS.url(s"$url$path").get().map(_.json).map { json =>
        block(json)
      }
      // TODO: Improve error handling and use a sane timeout
      Await.result(result, Duration.Inf)
    }
  }

  implicit val datumReads = Json.reads[Datum]
  implicit val hostReads = (
      (__ \ "name").read[String] and
      (__ \ "mainclasses").read[Set[String]] and
      (__ \ "stage").read[String] and
      (__ \ "group").read[String] and
      (__ \ "createdAt").read[DateTime] and
        (__ \ "instanceName").read[String] and
        (__ \ "internalName").read[String] and
        (__ \ "dnsName").read[String]
    ){ (name:String, mainclasses:Set[String], stage: String, group: String, createdAt: DateTime, instanceName: String, internalName: String, dnsName: String) =>
    Host(
      name = name,
      apps = mainclasses.map(App),
      stage = stage,
      tags = Map(
        "group" -> group,
        "created_at" -> createdAt.toString,
        "instancename" -> instanceName,
        "internalname" -> internalName,
        "dnsname" -> dnsName
      )
    )
  }

  def lastUpdated: DateTime = prism.get("/empty"){ json => (json \ "lastUpdated").as[DateTime] }

  def data = new Data {
    def keys: Seq[String] = prism.get("/data/keys"){ json => (json \ "data" \ "keys").as[Seq[String]] }
    def all: Map[String, Seq[Datum]] = prism.get("/data/list"){ json =>
      json \ "data" \ "data" match {
        case JsObject(fields) => fields.flatMap {
          case (key, JsArray(data)) =>
            Some(key -> data.map(datum => datum.as[Datum]))
          case _ =>
            None
        }.toMap
        case _ => Map.empty
      }
    }
    def datum(key: String, app: App, stage: Stage): Option[Datum] =
      prism.get(s"/data/lookup/$key?app=${app.name}&stage=${stage.name}"){ json => (json \ "data").asOpt[Datum] }
  }

  def instances = new Instances {
    def parseHosts(json: JsValue):Seq[Host] =
      (json \ "data" \ "instances").as[Seq[Host]].flatMap{ host =>
        host.apps match {
          case singleApp if singleApp.size == 1 => Seq(host)
          case noApps if noApps.isEmpty => None
          case multipleApps =>
            multipleApps.toSeq.map( app => host.copy(apps = Set(app)))
        }
      }

    def get(app: App, stage: Stage): Seq[Host] =
      prism.get(s"/instances?_expand&stage=${stage.name}&mainclasses=${app.name}")(parseHosts)
    def all: Seq[Host] = prism.get("/instances?_expand")(parseHosts)
  }

  def stages: Seq[String] = prism.get("/stages"){ json => (json \ "data" \ "stages").as[Seq[String]] }

  def secretProvider = LookupSelector.secretProvider
}