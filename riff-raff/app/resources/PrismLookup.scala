package resources

import magenta._
import org.joda.time.{DateTimeZone, DateTime}
import play.api.libs.ws.WS
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits._
import utils.Json._
import org.joda.time.format.DateTimeFormat
import scala.util.control.NonFatal
import controllers.Logging
import scala.util.{Failure, Success, Try}
import java.net.URLEncoder

object PrismLookup extends Lookup with MagentaCredentials with Logging {
  object prism extends Logging {
    val url = conf.Configuration.lookup.prismUrl
    def get[T](path: String, retriesLeft: Int = 5)(block: JsValue => T): T = {
      val result = WS.url(s"$url$path").get().map(_.json).map { json =>
        block(json)
      }
      try {
        Await.result(result, 3 seconds)
      } catch {
        case NonFatal(e) =>
          log.warn(s"Call to prism failed ($retriesLeft retries left)", e)
          if (retriesLeft > 0) get(path,retriesLeft-1)(block) else throw e
      }
    }
  }

  val formatter = DateTimeFormat.forPattern("EEE MMM dd HH:mm:ss 'UTC' yyyy")

  implicit val datumReads = Json.reads[Datum]
  implicit val hostReads = (
      (__ \ "name").read[String] and
      (__ \ "mainclasses").read[Set[String]] and
      (__ \ "stack").readNullable[String] and
      (__ \ "app").readNullable[Seq[String]] and
      (__ \ "stage").read[String] and
      (__ \ "group").read[String] and
      (__ \ "createdAt").read[DateTime] and
      (__ \ "instanceName").read[String] and
      (__ \ "internalName").read[String] and
      (__ \ "dnsName").read[String]
    ){ (name:String, mainclasses:Set[String], stack:Option[String], app:Option[Seq[String]],
        stage: String, group: String, createdAt: DateTime,
        instanceName: String, internalName: String, dnsName: String) =>
    val appSet:Set[App] = if (stack.isDefined && app.isDefined) {
      app.get.map(appName => StackApp(stack.get, appName)).toSet
    } else {
      mainclasses.map(LegacyApp)
    }
    Host(
      name = name,
      apps = appSet,
      stage = stage,
      tags = Map(
        "group" -> group,
        "created_at" -> formatter.print(createdAt.toDateTime(DateTimeZone.UTC)),
        "instancename" -> instanceName,
        "internalname" -> internalName,
        "dnsname" -> dnsName
      )
    )
  }
  implicit val dataReads = (
    (__ \ "key").read[String] and
      (__ \ "values").read[Seq[Datum]]
    ) tupled

  def name = "Prism"

  def lastUpdated: DateTime = prism.get("/sources?resource=instance"){ json =>
    val sourceCreatedAt = json \ "data" match {
      case JsArray(sources) => sources.map { source => (source \ "state" \ "createdAt").as[DateTime] }
      case _ => Seq(new DateTime(0))
    }
    sourceCreatedAt.minBy(_.getMillis)
  }

  def data = new Data {
    def keys: Seq[String] = prism.get("/data/keys"){ json => (json \ "data" \ "keys").as[Seq[String]] }
    def all: Map[String, Seq[Datum]] = prism.get("/data?_expand"){ json =>
      (json \ "data" \ "data").as[Seq[(String,Seq[Datum])]].toMap
    }
    def datum(key: String, app: App, stage: Stage): Option[Datum] = {
      val query = app match {
        case LegacyApp(appName) =>
          s"/data/lookup/${key.urlEncode}?app=${appName.urlEncode}&stage=${stage.name.urlEncode}"
        case StackApp(stackName, appName) =>
          s"/data/lookup/${key.urlEncode}?stack=${stackName.urlEncode}&app=${appName.urlEncode}&stage=${stage.name.urlEncode}"
      }
      prism.get(query){ json => (json \ "data").asOpt[Datum] }
    }

  }

  def instances = new Instances {
    def parseHosts(json: JsValue):Seq[Host] = {
      val tryHosts = (json \ "data" \ "instances").as[JsArray].value.map { jsHost =>
        Try {
          val host = jsHost.as[Host]
          host.apps match {
            case singleApp if singleApp.size == 1 => Seq(host)
            case noApps if noApps.isEmpty => Nil
            case multipleApps =>
              multipleApps.toSeq.map( app => host.copy(apps = Set(app)))
          }
        }
      }

      val errors = tryHosts.flatMap {
        case f@Failure(e) => Some(f)
        case _ => None
      }
      if (errors.size > 0) log.warn(s"Encountered ${errors.size} (of ${tryHosts.size}) instance records that could not be parsed in Prism response")
      if (log.isDebugEnabled) errors.foreach(e => log.debug("Couldn't parse instance from Prism data", e.exception))

      tryHosts.flatMap {
        case Success(hosts) => hosts
        case _ => Nil
      }
    }

    def get(app: App, stage: Stage): Seq[Host] = {
      val query = app match {
        case LegacyApp(appName) =>
          s"/instances?_expand&stage=${stage.name.urlEncode}&mainclasses=${appName.urlEncode}"
        case StackApp(stackName, appName) =>
          s"/instances?_expand&stage=${stage.name.urlEncode}&stack=${stackName.urlEncode}&app=${appName.urlEncode}"
      }
      prism.get(query)(parseHosts)
    }

    def all: Seq[Host] = prism.get("/instances?_expand")(parseHosts)
  }

  def stages: Seq[String] = prism.get("/stages"){ json => (json \ "data" \ "stages").as[Seq[String]] }

  def secretProvider = LookupSelector.secretProvider
}