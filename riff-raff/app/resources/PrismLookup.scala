package resources

import conf.Config
import controllers.Logging
import magenta._
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.libs.ws.WSClient
import utils.Json._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

case class Image(imageId: String, creationDate: DateTime, tags: Map[String, String])
object Image {
  implicit val formats: OFormat[Image] = Json.format[Image]
}

class PrismLookup(config: Config, wsClient: WSClient, secretProvider: SecretProvider) extends Lookup with Logging {

  def keyRing(stage: Stage, app: App, stack: Stack): KeyRing = {
    val KeyPattern = """credentials:(.*)""".r
    val apiCredentials = data.keys flatMap {
      case key@KeyPattern(service) =>
        data.datum(key, app, stage, stack).flatMap { data =>
          if(service.endsWith("role")) {
            Some(service -> ApiRoleCredentials(service, data.value, data.comment))
          } else {
            secretProvider.lookup(service, data.value).map { secret =>
              service -> ApiStaticCredentials(service, data.value, secret, data.comment)}
          }
        }
      case _ => None
    }
    KeyRing(apiCredentials.distinct.toMap)
  }

  object prism extends Logging {
    def get[T](path: String, retriesLeft: Int = 5)(block: JsValue => T): T = {
      val result = wsClient.url(s"${config.lookup.prismUrl}$path").get().map(_.json).map { json =>
        block(json)
      }
      try {
        Await.result(result, config.lookup.timeoutSeconds.seconds)
      } catch {
        case NonFatal(e) =>
          log.warn(s"Call to prism failed ($path; $retriesLeft retries left)", e)
          if (retriesLeft > 0) get(path,retriesLeft-1)(block) else throw e
      }
    }
  }

  val formatter: DateTimeFormatter = DateTimeFormat.forPattern("EEE MMM dd HH:mm:ss 'UTC' yyyy")

  implicit val datumReads = Json.reads[Datum]
  implicit val hostReads = (
      (__ \ "dnsName").read[String] and
      (__ \ "stack").read[String] and
      (__ \ "app").read[Seq[String]] and
      (__ \ "stage").read[String] and
      (__ \ "group").read[String] and
      (__ \ "createdAt").read[DateTime] and
      (__ \ "instanceName").readNullable[String] and
      (__ \ "internalName").readNullable[String] and
      (__ \ "dnsName").read[String]
    ){ (name:String, stack:String, appList:Seq[String],
        stage: String, group: String, createdAt: DateTime,
        instanceName: Option[String], internalName: Option[String], dnsName: String) =>
    val app:App = App(appList.head)
    val tags = {
      Map(
        "group" -> group,
        "created_at" -> formatter.print(createdAt.toDateTime(DateTimeZone.UTC)),
        "dnsname" -> dnsName
      ) ++
        instanceName.map("instancename" ->) ++
        internalName.map("internalname" ->)
    }
    Host(
      name = name,
      app = app,
      stage = stage,
      stack = stack,
      tags = tags
    )
  }
  implicit val dataReads = (
    (__ \ "key").read[String] and
      (__ \ "values").read[Seq[Datum]]
    ) tupled

  def name = "Prism"

  def lastUpdated: DateTime = prism.get("/sources?resource=instance"){ json =>
    val sourceCreatedAt = json \ "data" match {
      case JsDefined(JsArray(sources)) => sources.map { source => (source \ "state" \ "createdAt").as[DateTime] }
      case _ => Seq(new DateTime(0))
    }
    sourceCreatedAt.minBy(_.getMillis)
  }

  def data = new DataLookup {
    def keys: Seq[String] = prism.get("/data/keys"){ json => (json \ "data" \ "keys").as[Seq[String]] }
    def all: Map[String, Seq[Datum]] = prism.get("/data?_expand"){ json =>
      (json \ "data" \ "data").as[Seq[(String,Seq[Datum])]].toMap
    }
    def datum(key: String, app: App, stage: Stage, stack: Stack): Option[Datum] = {
      val query = s"/data/lookup/${key.urlEncode}?stack=${stack.name.urlEncode}&app=${app.name.urlEncode}&stage=${stage.name.urlEncode}"
      prism.get(query){ json => (json \ "data").asOpt[Datum] }
    }
  }

  def hosts = new HostLookup {
    def parseHosts(json: JsValue, entity: String = "instances"):Seq[Host] = {
      val tryHosts = (json \ "data" \ entity).as[JsArray].value.map { jsHost =>
        Try(jsHost.as[Host])
      }

      val errors = tryHosts.flatMap {
        case f@Failure(e) => Some(f)
        case _ => None
      }
      if (errors.nonEmpty) log.warn(s"Encountered ${errors.size} (of ${tryHosts.size}) $entity records that could not be parsed in Prism response")
      if (log.isDebugEnabled) errors.foreach(e => log.debug("Couldn't parse instance from Prism data", e.exception))

      tryHosts.flatMap {
        case Success(hosts) => Some(hosts)
        case _ => None
      }
    }

    def get(pkg: DeploymentPackage, app: App, parameters: DeployParameters, stack: Stack, entity: String): Seq[Host] = {
      val query = s"/$entity?_expand&stage=${parameters.stage.name.urlEncode}&stack=${stack.name.urlEncode}&app=${app.name.urlEncode}"
      prism.get(query)(js => parseHosts(js, entity))
    }

    def get(pkg: DeploymentPackage, app: App, parameters: DeployParameters, stack: Stack): Seq[Host] = {
      get(pkg, app, parameters, stack, "instances")
    }

    def all: Seq[Host] = prism.get("/instances?_expand")(js => parseHosts(js, "instances"))
  }

  def stages: Seq[String] = prism.get("/stages"){ json => (json \ "data" \ "stages").as[Seq[String]] }

  private def get(accountNumber: Option[String], region: String, tags: Map[String, String]): Seq[Image] = {
    val params: Seq[(String, String)] =
      tags.map { case (key, value) => s"tags.${key.urlEncode}" -> value }.toSeq ++
      accountNumber.map(acc => "meta.origin.accountNumber" -> acc) ++
      Map("region" -> region, "state" -> "available")
    val paramsQueryString = params.map { case (k,v) => s"$k=${v.urlEncode}" }.mkString("&")
    prism.get(s"/images?$paramsQueryString"){ json =>
      (json \ "data" \ "images").as[Seq[Image]]
    }
  }
  def getLatestAmi(accountNumber: Option[String], tagFilter: Map[String, String] => Boolean)(region: String)(tags: Map[String, String]): Option[String] =
    get(accountNumber, region, tags)
      .filter(image => tagFilter(image.tags))
      .filter(image => image.creationDate.isBefore(1596013200000L)) // drop anything after Wednesday 29th 9AM UTC
      .sortBy(-_.creationDate.getMillis)
      .headOption
      .map(_.imageId)

}
