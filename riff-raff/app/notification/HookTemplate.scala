package notification

import java.net.URLEncoder

import org.parboiled2._
import org.parboiled2.CharPredicate._
import persistence.{DeployRecordDocument}

class HookTemplate(val input: ParserInput, record: DeployRecordDocument, urlEncode: Boolean = false) extends Parser {
  def Template = rule{ NonToken ~ (zeroOrMore(SubstitutionToken ~ NonToken ~> (_ + _)) ~> (_.mkString)) ~> (_ + _) }

  def NonToken = rule { capture(zeroOrMore(!SubstitutionToken ~ ANY))  }

  def SubstitutionToken = rule { "%deploy." ~ Param ~ '%' }

  def Param = rule { BuildId | ProjectName | Stage | Recipe | HostList | Deployer | UUID | Tag }

  def BuildId = rule { ("buildId" | "build") ~ push(encode(record.parameters.buildId)) }

  def ProjectName = rule { ("projectName" | "project") ~ push(encode(record.parameters.projectName)) }

  def Stage = rule { "stage" ~ push(encode(record.parameters.stage)) }

  def Recipe = rule { "recipe" ~ push(encode(record.parameters.recipe)) }

  def HostList = rule { ("hostList" | "hosts") ~ push(encode(record.parameters.hostList.mkString(",")))}

  def Deployer = rule { "deployer" ~ push(encode(record.parameters.deployer)) }

  def UUID = rule { "uuid" ~ push(encode(record.uuid.toString)) }

  def Tag = rule { "tag." ~ capture( oneOrMore(Alpha | '-' | '_')) ~> (tagName =>
    encode(record.parameters.tags.getOrElse(tagName, ""))
  )}

  def encode(p: String) = if (urlEncode) URLEncoder.encode(p, "utf-8") else p
}
