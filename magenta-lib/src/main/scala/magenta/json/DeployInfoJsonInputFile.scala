package magenta
package json

import net.liftweb.json._
import io.Source
import java.io.File

case class DeployInfoJsonInputFile(
  hosts: List[DeployInfoHost],
  keys: List[DeployInfoKey]
)


case class DeployInfoHost(
  hostname: String,
  app: String,
  group: String,
  stage: String
)

case class DeployInfoKey(
  app: String,
  stage: String,
  accesskey: String,
  comment: Option[String]
)


object DeployInfoJsonReader {
  private implicit val formats = DefaultFormats

  def parse(f: File): DeployInfo = parse(Source.fromFile(f).mkString)

  def parse(inputFile: DeployInfoJsonInputFile): DeployInfo = DeployInfo(inputFile)

  def parse(s: String): DeployInfo = {
    parse(Extraction.extract[DeployInfoJsonInputFile](JsonParser.parse(s)))
  }

}


