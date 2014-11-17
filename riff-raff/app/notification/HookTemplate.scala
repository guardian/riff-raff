package notification

import java.net.URLEncoder

import org.parboiled2._
import org.parboiled2.CharPredicate._
import persistence.{DeployRecordDocument}

class HookTemplate(val input: ParserInput, record: DeployRecordDocument, urlEncode: Boolean = false) extends Parser {
  def Template = rule{ NonToken ~ (zeroOrMore(SubstitutionToken ~ NonToken ~> (_ + _)) ~> (_.mkString)) ~> (_ + _) }

  def NonToken = rule { capture(zeroOrMore(!SubstitutionToken ~ ANY))  }

  def SubstitutionToken = rule { "%deploy." ~ Param ~ '%' }

  def Param = rule { SimpleParam | Tag }

  def SimpleParam = rule { paramSubstitutions.mapValues(encode) }

  def Tag = rule { "tag." ~ capture( oneOrMore(Alpha | '-' | '_')) ~> (tagName =>
    encode(record.parameters.tags.getOrElse(tagName, ""))
  )}

  def paramSubstitutions: Map[String, String] = Map(
    "buildId" -> record.parameters.buildId,
    "build" -> record.parameters.buildId,
    "projectName" -> record.parameters.projectName,
    "project" -> record.parameters.projectName,
    "stage" -> record.parameters.stage,
    "recipe" -> record.parameters.recipe,
    "hostList" ->  record.parameters.hostList.mkString(","),
    "deployer" -> record.parameters.deployer,
    "uuid" -> record.uuid.toString
  )

  def encode(p: String) = if (urlEncode) URLEncoder.encode(p, "utf-8") else p
}
