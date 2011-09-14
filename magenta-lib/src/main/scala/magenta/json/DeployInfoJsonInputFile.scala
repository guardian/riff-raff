package magenta
package json

import net.liftweb.json._
import io.Source
import java.io.File

case class DeployInfoJsonInputFile(
  hosts: List[DeployInfoHost]
)


case class DeployInfoHost(
  hostname: String,
  app: String,
  group: String,
  stage: String
)


object DeployInfoJsonReader {
  private implicit val formats = DefaultFormats

  def parse(f: File):  List[Host] = parse(Source.fromFile(f).mkString)

  def parse(inputFile: DeployInfoJsonInputFile): List[Host] = {
    inputFile.hosts map { host => Host(host.hostname, Set(App(host.app)), host.stage) }
  }

  def parse(s: String): List[Host] = {
    parse(Extraction.extract[DeployInfoJsonInputFile](JsonParser.parse(s)))
  }

}


