package magenta
package json

import net.liftweb.json._
import io.Source
import java.io.File

case class DeployInfoJsonInputFile(
  hosts: List[DeployInfoHost],
  keys: Option[List[DeployInfoKey]],
  data: Map[String,List[DeployInfoData]]
)

case class DeployInfoHost(
  hostname: String,
  app: String,
  group: String,
  stage: String,
  instancename: Option[String],
  internalname: Option[String],
  dnsname: Option[String],
  created_at: Option[String]
)

case class DeployInfoKey(
  app: String,
  stage: String,
  accesskey: String,
  comment: Option[String]
)

case class DeployInfoData(
  app: String,
  stage: String,
  value: String,
  comment: Option[String]
)

object DeployInfoJsonReader {
  private implicit val formats = DefaultFormats

  def parse(f: File): DeployInfo = parse(Source.fromFile(f).mkString)

  def parse(inputFile: DeployInfoJsonInputFile): DeployInfo = DeployInfo(inputFile)

  def parse(json: JValue): DeployInfo = parse(Extraction.extract[DeployInfoJsonInputFile](json))

  def parse(s: String): DeployInfo = parse(JsonParser.parse(s))

}


