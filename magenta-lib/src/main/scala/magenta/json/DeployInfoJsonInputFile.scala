package magenta
package json

import net.liftweb.json._
import host.HostProvider

case class DeployInfoJsonInputFile(
  hosts: List[DeployInfoHost]
)


case class DeployInfoHost(
  hostname: String,
  app: String,
  group: String,
  stage: String
)


class DeployInfoJsonHostProvider(jsonString: String) extends HostProvider {
  private implicit val formats = DefaultFormats

  private def parse(inputFile: DeployInfoJsonInputFile): List[Host] = {
    inputFile.hosts map { host => Host(host.hostname, Set(App(host.app)), host.stage) }
  }

  def parse(s: String): List[Host] = {
    parse(Extraction.extract[DeployInfoJsonInputFile](JsonParser.parse(s)))
  }

  def hosts = parse(jsonString)
}


